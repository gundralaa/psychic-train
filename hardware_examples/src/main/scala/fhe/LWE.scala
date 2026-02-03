package fhe

import chisel3._
import chisel3.util._

/**
  * Learning With Errors (LWE) Encryption Primitives
  * 
  * LWE is the foundation of lattice-based cryptography and FHE.
  * An LWE sample is a tuple (a, b) where:
  *   - a ∈ Z_q^n is a random vector
  *   - b = <a, s> + e + m*q/2 (mod q)
  *   - s ∈ Z_q^n is the secret key
  *   - e is a small error/noise term
  *   - m is the message bit (for binary messages)
  * 
  * The security of LWE comes from the difficulty of distinguishing
  * (a, <a,s> + e) from uniform random (a, b).
  */

/**
  * LWE Ciphertext
  * 
  * Represents an LWE encryption of a single bit.
  * The ciphertext is (a, b) where a ∈ Z_q^n and b ∈ Z_q.
  */
class LWECiphertext(n: Int, width: Int) extends Bundle {
  val a = Vec(n, UInt(width.W))
  val b = UInt(width.W)
}

/**
  * LWE Secret Key
  * 
  * The secret key is a vector s ∈ {0, 1}^n (binary) or s ∈ Z_q^n.
  * Using binary keys is more efficient and commonly used.
  */
class LWESecretKey(n: Int, width: Int) extends Bundle {
  val s = Vec(n, UInt(width.W))
}

/**
  * LWE Inner Product Unit
  * 
  * Computes the inner product <a, s> = Σ a_i * s_i mod q.
  * This is the core operation in LWE encryption and decryption.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class LWEInnerProduct(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val s = Input(Vec(n, UInt(width.W)))
    val start = Input(Bool())
    val result = Output(UInt(width.W))
    val done = Output(Bool())
  })
  
  // Accumulator with extra bits to prevent overflow
  val accWidth = width + log2Ceil(n) + 1
  val acc = RegInit(0.U(accWidth.W))
  val counter = RegInit(0.U(log2Ceil(n + 1).W))
  val busy = RegInit(false.B)
  
  io.done := false.B
  
  when(io.start && !busy) {
    acc := 0.U
    counter := 0.U
    busy := true.B
  }
  
  when(busy) {
    when(counter < n.U) {
      val product = io.a(counter) * io.s(counter)
      acc := acc + product
      counter := counter + 1.U
    }.otherwise {
      busy := false.B
      io.done := true.B
    }
  }
  
  // Reduce modulo q
  io.result := (acc % modulus.U)(width - 1, 0)
}

/**
  * LWE Encryption Unit
  * 
  * Encrypts a single bit m as:
  *   (a, b) where b = <a, s> + e + m*Δ mod q
  * 
  * Here Δ = q/2 encodes the message in the MSB.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class LWEEncrypt(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))         // Random vector (externally generated)
    val secretKey = Input(Vec(n, UInt(width.W))) // Secret key s
    val noise = Input(UInt(width.W))              // Error term e (externally generated)
    val message = Input(Bool())                   // Plaintext bit m
    val start = Input(Bool())
    val ciphertext = Output(new LWECiphertext(n, width))
    val done = Output(Bool())
  })
  
  // Message encoding: Δ = q/2
  val delta = (modulus / 2).U(width.W)
  
  // Inner product computation
  val innerProd = Module(new LWEInnerProduct(n, width, modulus))
  innerProd.io.a := io.a
  innerProd.io.s := io.secretKey
  innerProd.io.start := io.start
  
  // State machine
  val sIdle :: sCompute :: sDone = Enum(3)
  val state = RegInit(sIdle)
  
  val resultReg = Reg(new LWECiphertext(n, width))
  
  io.done := (state === sDone)
  io.ciphertext := resultReg
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        resultReg.a := io.a
        state := sCompute
      }
    }
    is(sCompute) {
      when(innerProd.io.done) {
        // b = <a, s> + e + m*Δ mod q
        val msgEnc = Mux(io.message, delta, 0.U)
        val sum = innerProd.io.result + io.noise + msgEnc
        resultReg.b := Mux(sum >= modulus.U, sum - modulus.U, sum)
        state := sDone
      }
    }
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * LWE Decryption Unit
  * 
  * Decrypts a ciphertext (a, b) by computing:
  *   m' = b - <a, s> mod q
  * Then rounds to the nearest multiple of Δ = q/2 to recover the bit.
  * 
  * If m' is closer to 0, output 0; if closer to q/2, output 1.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class LWEDecrypt(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val ciphertext = Input(new LWECiphertext(n, width))
    val secretKey = Input(Vec(n, UInt(width.W)))
    val start = Input(Bool())
    val plaintext = Output(Bool())
    val done = Output(Bool())
  })
  
  // Inner product computation
  val innerProd = Module(new LWEInnerProduct(n, width, modulus))
  innerProd.io.a := io.ciphertext.a
  innerProd.io.s := io.secretKey
  innerProd.io.start := io.start
  
  val quarter = (modulus / 4).U
  val threeQuarter = (3 * modulus / 4).U
  
  // State machine
  val sIdle :: sCompute :: sDone = Enum(3)
  val state = RegInit(sIdle)
  val plaintextReg = RegInit(false.B)
  
  io.done := (state === sDone)
  io.plaintext := plaintextReg
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sCompute
      }
    }
    is(sCompute) {
      when(innerProd.io.done) {
        // phase = b - <a, s> mod q
        val phaseSigned = io.ciphertext.b.asSInt - innerProd.io.result.asSInt
        val phase = Mux(phaseSigned < 0.S, 
                       (phaseSigned + modulus.S).asUInt, 
                       phaseSigned.asUInt)
        
        // Decode: phase in [q/4, 3q/4) -> 1, else -> 0
        plaintextReg := phase >= quarter && phase < threeQuarter
        state := sDone
      }
    }
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * LWE Ciphertext Addition
  * 
  * Adds two LWE ciphertexts homomorphically:
  *   (a1, b1) + (a2, b2) = (a1 + a2, b1 + b2) mod q
  * 
  * This computes the XOR of the underlying plaintexts.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class LWEAdd(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val ct1 = Input(new LWECiphertext(n, width))
    val ct2 = Input(new LWECiphertext(n, width))
    val result = Output(new LWECiphertext(n, width))
  })
  
  // Add vectors element-wise
  for (i <- 0 until n) {
    val sum = io.ct1.a(i) +& io.ct2.a(i)
    io.result.a(i) := Mux(sum >= modulus.U, sum - modulus.U, sum)
  }
  
  // Add scalars
  val bSum = io.ct1.b +& io.ct2.b
  io.result.b := Mux(bSum >= modulus.U, bSum - modulus.U, bSum)
}

/**
  * LWE Ciphertext Subtraction
  * 
  * Subtracts two LWE ciphertexts:
  *   (a1, b1) - (a2, b2) = (a1 - a2, b1 - b2) mod q
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class LWESubtract(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val ct1 = Input(new LWECiphertext(n, width))
    val ct2 = Input(new LWECiphertext(n, width))
    val result = Output(new LWECiphertext(n, width))
  })
  
  for (i <- 0 until n) {
    val diff = io.ct1.a(i).asSInt - io.ct2.a(i).asSInt
    io.result.a(i) := Mux(diff < 0.S, (diff + modulus.S).asUInt, diff.asUInt)
  }
  
  val bDiff = io.ct1.b.asSInt - io.ct2.b.asSInt
  io.result.b := Mux(bDiff < 0.S, (bDiff + modulus.S).asUInt, bDiff.asUInt)
}

/**
  * LWE Key Switching
  * 
  * Converts an LWE ciphertext under key s1 to one under key s2.
  * Uses a key switching key (KSK) which is a sequence of LWE encryptions.
  * 
  * The KSK for switching from s1 to s2 contains encryptions under s2 of
  * scaled bits of s1: E_{s2}(s1[i] * 2^j) for each key bit and digit.
  * 
  * @param n LWE dimension
  * @param width Bit width
  * @param modulus The modulus q
  * @param baseKS Key switching base
  * @param logKS Number of digits
  */
class LWEKeySwitch(n: Int, width: Int, modulus: Int, baseKS: Int, logKS: Int) extends Module {
  val io = IO(new Bundle {
    val ctIn = Input(new LWECiphertext(n, width))
    // KSK: n entries, each with logKS LWE ciphertexts
    val ksk = Input(Vec(n, Vec(logKS, new LWECiphertext(n, width))))
    val start = Input(Bool())
    val ctOut = Output(new LWECiphertext(n, width))
    val done = Output(Bool())
  })
  
  // State machine
  val sIdle :: sProcess :: sDone = Enum(3)
  val state = RegInit(sIdle)
  
  // Accumulators
  val accA = Reg(Vec(n, UInt(width.W)))
  val accB = Reg(UInt(width.W))
  
  // Counters
  val iCounter = RegInit(0.U(log2Ceil(n + 1).W))
  val jCounter = RegInit(0.U(log2Ceil(logKS + 1).W))
  
  io.done := (state === sDone)
  io.ctOut.a := accA
  io.ctOut.b := accB
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        // Initialize: start with (0, ctIn.b)
        for (i <- 0 until n) {
          accA(i) := 0.U
        }
        accB := io.ctIn.b
        iCounter := 0.U
        jCounter := 0.U
        state := sProcess
      }
    }
    
    is(sProcess) {
      // Extract digit j of ctIn.a[i] in base baseKS
      val aVal = io.ctIn.a(iCounter)
      val digit = (aVal >> (jCounter * log2Ceil(baseKS).U))(log2Ceil(baseKS) - 1, 0)
      
      // Subtract digit * KSK[i][j] from accumulator
      when(digit =/= 0.U) {
        val kskEntry = io.ksk(iCounter)(jCounter)
        for (k <- 0 until n) {
          val prod = (kskEntry.a(k) * digit) % modulus.U
          val newVal = accA(k).asSInt - prod.asSInt
          accA(k) := Mux(newVal < 0.S, (newVal + modulus.S).asUInt, newVal.asUInt)
        }
        val prodB = (kskEntry.b * digit) % modulus.U
        val newB = accB.asSInt - prodB.asSInt
        accB := Mux(newB < 0.S, (newB + modulus.S).asUInt, newB.asUInt)
      }
      
      // Update counters
      when(jCounter === (logKS - 1).U) {
        jCounter := 0.U
        when(iCounter === (n - 1).U) {
          state := sDone
        }.otherwise {
          iCounter := iCounter + 1.U
        }
      }.otherwise {
        jCounter := jCounter + 1.U
      }
    }
    
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * LWE Sample Extractor
  * 
  * Extracts an LWE sample from an RLWE sample.
  * This is used in the bootstrapping procedure.
  * 
  * Given RLWE sample (a(X), b(X)), extracts LWE sample for coefficient k:
  *   a' = (a_k, -a_{k-1}, ..., -a_1, a_0, a_{N-1}, ..., a_{k+1})
  *   b' = b_k
  * 
  * @param N Ring dimension
  * @param width Bit width
  * @param modulus The modulus q
  */
class LWESampleExtract(N: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val rlweA = Input(Vec(N, UInt(width.W)))
    val rlweB = Input(Vec(N, UInt(width.W)))
    val extractIdx = Input(UInt(log2Ceil(N).W))
    val lweOut = Output(new LWECiphertext(N, width))
  })
  
  val k = io.extractIdx
  
  // Extract b_k
  io.lweOut.b := io.rlweB(k)
  
  // Build the LWE 'a' vector from RLWE 'a' polynomial
  for (i <- 0 until N) {
    val srcIdx = Wire(UInt(log2Ceil(N).W))
    val negate = Wire(Bool())
    
    when(i.U <= k) {
      srcIdx := k - i.U
      negate := false.B
    }.otherwise {
      srcIdx := (N.U + k - i.U)(log2Ceil(N) - 1, 0)
      negate := true.B
    }
    
    val coef = io.rlweA(srcIdx)
    io.lweOut.a(i) := Mux(negate && coef =/= 0.U, modulus.U - coef, coef)
  }
}

/**
  * Trivial LWE Encryption
  * 
  * Creates a trivial (noiseless) LWE encryption of a value:
  *   (0, m) encrypts m under any key
  * 
  * This is useful for adding plaintext constants to ciphertexts.
  * 
  * @param n LWE dimension
  * @param width Bit width
  */
class TrivialLWEEncrypt(n: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val message = Input(UInt(width.W))
    val ciphertext = Output(new LWECiphertext(n, width))
  })
  
  for (i <- 0 until n) {
    io.ciphertext.a(i) := 0.U
  }
  io.ciphertext.b := io.message
}

object LWEMain extends App {
  val n = 512
  val width = 10
  val q = 1024
  
  emitVerilog(new LWEEncrypt(n, width, q), Array("--target-dir", "generated/fhe"))
  emitVerilog(new LWEDecrypt(n, width, q), Array("--target-dir", "generated/fhe"))
  emitVerilog(new LWEAdd(n, width, q), Array("--target-dir", "generated/fhe"))
}
