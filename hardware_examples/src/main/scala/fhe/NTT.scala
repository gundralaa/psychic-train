package fhe

import chisel3._
import chisel3.util._

/**
  * Number Theoretic Transform (NTT)
  * 
  * NTT is the modular arithmetic equivalent of FFT, used for fast
  * polynomial multiplication in ring-based cryptography.
  * 
  * For polynomials of degree N-1 in Z_q[X]/(X^N + 1):
  *   - Forward NTT: Convert polynomial to evaluation representation
  *   - Inverse NTT: Convert back to coefficient representation
  *   - Multiplication in NTT domain is element-wise
  * 
  * This enables O(N log N) polynomial multiplication instead of O(N^2).
  */

/**
  * NTT Parameters
  * 
  * For NTT over Z_q[X]/(X^N + 1), we need:
  *   - q ≡ 1 (mod 2N) for primitive 2N-th root of unity to exist
  *   - N is a power of 2
  * 
  * Common parameters for FHE:
  *   - N = 1024, q = 12289 (used in many lattice schemes)
  *   - N = 2048, q = 7681
  */
object NTTParams {
  // Default: N = 256, q = 7681 (a common FHE prime, 7681 = 1 + 30*256)
  val defaultN = 256
  val defaultQ = 7681
  val defaultLogQ = 13
  
  // Primitive root of unity for N=256, q=7681
  // omega = 62 is a primitive 512th root of unity mod 7681
  val defaultOmega = 62
  
  // Precomputed twiddle factors would go here
  // In practice, these are stored in ROM or computed once at startup
}

/**
  * Butterfly Unit
  * 
  * Core computational unit of NTT, performing the operation:
  *   a' = a + w*b (mod q)
  *   b' = a - w*b (mod q)
  * 
  * where w is a twiddle factor (power of primitive root of unity).
  * 
  * @param width Bit width of operands
  * @param modulus The prime modulus q
  */
class Butterfly(width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a_in = Input(UInt(width.W))
    val b_in = Input(UInt(width.W))
    val twiddle = Input(UInt(width.W))
    val a_out = Output(UInt(width.W))
    val b_out = Output(UInt(width.W))
  })
  
  // Compute w*b mod q
  val wb = (io.b_in * io.twiddle) % modulus.U
  
  // a + wb mod q
  val sum = io.a_in + wb
  io.a_out := Mux(sum >= modulus.U, sum - modulus.U, sum)
  
  // a - wb mod q (add modulus if negative)
  val diffSigned = io.a_in.asSInt - wb.asSInt
  io.b_out := Mux(diffSigned < 0.S, (diffSigned + modulus.S).asUInt, diffSigned.asUInt)
}

/**
  * NTT Stage
  * 
  * One stage of the NTT algorithm, processing all butterflies at a given level.
  * 
  * @param n Polynomial degree (power of 2)
  * @param stage Stage number (0 to log2(n)-1)
  * @param width Bit width
  * @param modulus Prime modulus
  */
class NTTStage(n: Int, stage: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(n, UInt(width.W)))
    val twiddles = Input(Vec(n/2, UInt(width.W)))
    val out = Output(Vec(n, UInt(width.W)))
  })
  
  val logN = log2Ceil(n)
  val halfN = n / 2
  
  // Number of groups and butterflies per group at this stage
  val numGroups = 1 << stage
  val butterfliesPerGroup = halfN >> stage
  
  // Create butterfly units
  val butterflies = Seq.fill(halfN)(Module(new Butterfly(width, modulus)))
  
  // Connect butterflies based on stage
  var bfIdx = 0
  for (g <- 0 until numGroups) {
    val groupStart = g * (butterfliesPerGroup * 2)
    for (b <- 0 until butterfliesPerGroup) {
      val aIdx = groupStart + b
      val bIdx = aIdx + butterfliesPerGroup
      val twiddleIdx = b * numGroups
      
      butterflies(bfIdx).io.a_in := io.in(aIdx)
      butterflies(bfIdx).io.b_in := io.in(bIdx)
      butterflies(bfIdx).io.twiddle := io.twiddles(twiddleIdx % (n/2))
      
      io.out(aIdx) := butterflies(bfIdx).io.a_out
      io.out(bIdx) := butterflies(bfIdx).io.b_out
      
      bfIdx += 1
    }
  }
}

/**
  * Iterative NTT Unit
  * 
  * Computes NTT using a sequential/iterative approach with a single
  * butterfly unit, trading area for time. Suitable for resource-constrained
  * implementations.
  * 
  * @param n Polynomial degree
  * @param width Bit width
  * @param modulus Prime modulus
  */
class IterativeNTT(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(n, UInt(width.W)))
    val twiddles = Input(Vec(n, UInt(width.W)))  // All twiddle factors
    val start = Input(Bool())
    val inverse = Input(Bool())  // True for inverse NTT
    val out = Output(Vec(n, UInt(width.W)))
    val done = Output(Bool())
  })
  
  val logN = log2Ceil(n)
  val halfN = n / 2
  
  // State
  val sIdle :: sCompute :: sDone = Enum(3)
  val state = RegInit(sIdle)
  
  // Working memory
  val data = Reg(Vec(n, UInt(width.W)))
  
  // Counters
  val stage = RegInit(0.U(logN.W))
  val bfCounter = RegInit(0.U(log2Ceil(halfN + 1).W))
  
  // Butterfly unit
  val butterfly = Module(new Butterfly(width, modulus))
  
  // Index computation for current butterfly
  val numGroups = 1.U << stage
  val butterfliesPerGroup = (halfN.U >> stage)
  val groupIdx = bfCounter / butterfliesPerGroup
  val bfInGroup = bfCounter % butterfliesPerGroup
  val groupStart = groupIdx * (butterfliesPerGroup << 1.U)
  val aIdx = (groupStart + bfInGroup)(log2Ceil(n) - 1, 0)
  val bIdx = (aIdx + butterfliesPerGroup)(log2Ceil(n) - 1, 0)
  val twiddleIdx = (bfInGroup * numGroups)(log2Ceil(n) - 1, 0)
  
  // Connect butterfly
  butterfly.io.a_in := data(aIdx)
  butterfly.io.b_in := data(bIdx)
  butterfly.io.twiddle := Mux(io.inverse, 
    io.twiddles(n.U - twiddleIdx),  // Inverse uses conjugate twiddles
    io.twiddles(twiddleIdx))
  
  io.done := (state === sDone)
  io.out := data
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        // Bit-reverse the input
        for (i <- 0 until n) {
          val revIdx = bitReverse(i, logN)
          data(revIdx) := io.in(i)
        }
        stage := 0.U
        bfCounter := 0.U
        state := sCompute
      }
    }
    
    is(sCompute) {
      // Update data with butterfly result
      data(aIdx) := butterfly.io.a_out
      data(bIdx) := butterfly.io.b_out
      
      when(bfCounter === (halfN - 1).U) {
        bfCounter := 0.U
        when(stage === (logN - 1).U) {
          state := sDone
        }.otherwise {
          stage := stage + 1.U
        }
      }.otherwise {
        bfCounter := bfCounter + 1.U
      }
    }
    
    is(sDone) {
      // Optionally scale by 1/N for inverse NTT
      when(io.inverse) {
        // In practice, we would multiply by N^(-1) mod q
        // For simplicity, we leave scaling to the user
      }
      state := sIdle
    }
  }
  
  // Bit reversal function
  private def bitReverse(x: Int, bits: Int): Int = {
    var result = 0
    var value = x
    for (_ <- 0 until bits) {
      result = (result << 1) | (value & 1)
      value >>= 1
    }
    result
  }
}

/**
  * Parallel NTT Unit
  * 
  * Computes NTT using parallel butterfly units for higher throughput.
  * This is a fully pipelined design that can accept a new input every cycle
  * after the initial latency.
  * 
  * @param n Polynomial degree
  * @param width Bit width  
  * @param modulus Prime modulus
  */
class ParallelNTT(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(n, UInt(width.W)))
    val twiddles = Input(Vec(n, UInt(width.W)))
    val start = Input(Bool())
    val inverse = Input(Bool())
    val out = Output(Vec(n, UInt(width.W)))
    val valid = Output(Bool())
  })
  
  val logN = log2Ceil(n)
  val halfN = n / 2
  
  // Pipeline registers for each stage
  val pipeRegs = Seq.fill(logN + 1)(Reg(Vec(n, UInt(width.W))))
  val validRegs = RegInit(VecInit(Seq.fill(logN + 1)(false.B)))
  
  // First stage: bit-reverse input
  when(io.start) {
    for (i <- 0 until n) {
      val revIdx = bitReverse(i, logN)
      pipeRegs(0)(revIdx) := io.in(i)
    }
    validRegs(0) := true.B
  }.otherwise {
    validRegs(0) := false.B
  }
  
  // NTT stages
  for (s <- 0 until logN) {
    val numGroups = 1 << s
    val butterfliesPerGroup = halfN >> s
    val butterflies = Seq.fill(halfN)(Module(new Butterfly(width, modulus)))
    
    var bfIdx = 0
    for (g <- 0 until numGroups) {
      val groupStart = g * (butterfliesPerGroup * 2)
      for (b <- 0 until butterfliesPerGroup) {
        val aIdx = groupStart + b
        val bIdx = aIdx + butterfliesPerGroup
        val twiddleIdx = b * numGroups
        
        butterflies(bfIdx).io.a_in := pipeRegs(s)(aIdx)
        butterflies(bfIdx).io.b_in := pipeRegs(s)(bIdx)
        butterflies(bfIdx).io.twiddle := Mux(io.inverse,
          io.twiddles((n - twiddleIdx) % n),
          io.twiddles(twiddleIdx % n))
        
        pipeRegs(s + 1)(aIdx) := butterflies(bfIdx).io.a_out
        pipeRegs(s + 1)(bIdx) := butterflies(bfIdx).io.b_out
        
        bfIdx += 1
      }
    }
    
    validRegs(s + 1) := validRegs(s)
  }
  
  io.out := pipeRegs(logN)
  io.valid := validRegs(logN)
  
  private def bitReverse(x: Int, bits: Int): Int = {
    var result = 0
    var value = x
    for (_ <- 0 until bits) {
      result = (result << 1) | (value & 1)
      value >>= 1
    }
    result
  }
}

/**
  * NTT-based Polynomial Multiplier
  * 
  * Multiplies two polynomials in Z_q[X]/(X^N + 1) using NTT:
  *   c(X) = a(X) * b(X) mod (X^N + 1)
  * 
  * Algorithm:
  *   1. Compute NTT(a) and NTT(b)
  *   2. Point-wise multiply: c_ntt[i] = a_ntt[i] * b_ntt[i]
  *   3. Compute c = INTT(c_ntt)
  * 
  * @param n Polynomial degree
  * @param width Bit width
  * @param modulus Prime modulus
  */
class NTTPolynomialMultiplier(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val b = Input(Vec(n, UInt(width.W)))
    val twiddles = Input(Vec(n, UInt(width.W)))
    val invTwiddles = Input(Vec(n, UInt(width.W)))
    val start = Input(Bool())
    val out = Output(Vec(n, UInt(width.W)))
    val done = Output(Bool())
  })
  
  // State machine
  val sIdle :: sNttA :: sNttB :: sMult :: sIntt :: sDone = Enum(6)
  val state = RegInit(sIdle)
  
  // Storage
  val aNtt = Reg(Vec(n, UInt(width.W)))
  val bNtt = Reg(Vec(n, UInt(width.W)))
  val cNtt = Reg(Vec(n, UInt(width.W)))
  val result = Reg(Vec(n, UInt(width.W)))
  
  // NTT unit (reused for forward and inverse)
  val ntt = Module(new IterativeNTT(n, width, modulus))
  
  // Point-wise multiplication counter
  val multCounter = RegInit(0.U(log2Ceil(n + 1).W))
  
  // Connect NTT inputs based on state
  ntt.io.in := Mux(state === sNttA, io.a,
               Mux(state === sNttB, io.b, cNtt))
  ntt.io.twiddles := Mux(state === sIntt, io.invTwiddles, io.twiddles)
  ntt.io.inverse := (state === sIntt)
  ntt.io.start := (state === sNttA || state === sNttB || state === sIntt) && 
                  RegNext(state) =/= state  // Start pulse
  
  io.done := (state === sDone)
  io.out := result
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sNttA
        multCounter := 0.U
      }
    }
    
    is(sNttA) {
      when(ntt.io.done) {
        aNtt := ntt.io.out
        state := sNttB
      }
    }
    
    is(sNttB) {
      when(ntt.io.done) {
        bNtt := ntt.io.out
        state := sMult
      }
    }
    
    is(sMult) {
      // Point-wise multiplication
      cNtt(multCounter) := (aNtt(multCounter) * bNtt(multCounter)) % modulus.U
      
      when(multCounter === (n - 1).U) {
        state := sIntt
      }.otherwise {
        multCounter := multCounter + 1.U
      }
    }
    
    is(sIntt) {
      when(ntt.io.done) {
        result := ntt.io.out
        state := sDone
      }
    }
    
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * Twiddle Factor ROM
  * 
  * Stores precomputed twiddle factors (powers of primitive root of unity).
  * For NTT with X^N + 1, we need powers of a primitive 2N-th root of unity.
  * 
  * @param n Number of coefficients
  * @param width Bit width
  * @param omega Primitive 2N-th root of unity
  * @param modulus Prime modulus
  */
class TwiddleROM(n: Int, width: Int, omega: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val idx = Input(UInt(log2Ceil(n).W))
    val twiddle = Output(UInt(width.W))
    val invTwiddle = Output(UInt(width.W))
  })
  
  // Precompute twiddle factors at elaboration time
  val twiddles = computeTwiddles(n, omega, modulus)
  val invTwiddles = computeInvTwiddles(n, omega, modulus)
  
  // ROM lookup
  val twiddleROM = VecInit(twiddles.map(_.U(width.W)))
  val invTwiddleROM = VecInit(invTwiddles.map(_.U(width.W)))
  
  io.twiddle := twiddleROM(io.idx)
  io.invTwiddle := invTwiddleROM(io.idx)
  
  private def computeTwiddles(n: Int, omega: Int, q: Int): Seq[Int] = {
    var w = 1
    (0 until n).map { i =>
      val result = w
      w = (w.toLong * omega % q).toInt
      result
    }
  }
  
  private def computeInvTwiddles(n: Int, omega: Int, q: Int): Seq[Int] = {
    // Inverse of omega
    val omegaInv = modInverse(omega, q)
    var w = 1
    (0 until n).map { i =>
      val result = w
      w = (w.toLong * omegaInv % q).toInt
      result
    }
  }
  
  private def modInverse(a: Int, m: Int): Int = {
    // Extended Euclidean algorithm
    var t1 = 0
    var t2 = 1
    var r1 = m
    var r2 = a
    while (r2 != 0) {
      val q = r1 / r2
      val temp = t1 - q * t2
      t1 = t2
      t2 = temp
      val rTemp = r1 - q * r2
      r1 = r2
      r2 = rTemp
    }
    if (t1 < 0) t1 + m else t1
  }
}

object NTTMain extends App {
  emitVerilog(new Butterfly(13, 7681), Array("--target-dir", "generated/fhe"))
  emitVerilog(new IterativeNTT(64, 13, 7681), Array("--target-dir", "generated/fhe"))
  emitVerilog(new TwiddleROM(64, 13, 62, 7681), Array("--target-dir", "generated/fhe"))
}
