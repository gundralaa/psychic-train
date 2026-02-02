package fhe

import chisel3._
import chisel3.util._

/**
  * Polynomial Arithmetic Unit
  * 
  * Performs arithmetic operations on polynomials in the ring Z_q[X]/(X^N + 1).
  * This ring is central to Ring-LWE based FHE schemes like TFHE.
  * 
  * Operations:
  *   - Addition: coefficient-wise addition mod q
  *   - Subtraction: coefficient-wise subtraction mod q  
  *   - Negation: negate all coefficients
  *   - Scalar multiplication: multiply all coefficients by a scalar
  *   - Polynomial multiplication: using NTT for efficiency
  */

/**
  * Polynomial Addition
  * 
  * Adds two polynomials coefficient-wise modulo q.
  * c(X) = a(X) + b(X)
  * 
  * @param n Polynomial degree
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class PolynomialAdder(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val b = Input(Vec(n, UInt(width.W)))
    val out = Output(Vec(n, UInt(width.W)))
  })
  
  for (i <- 0 until n) {
    val sum = io.a(i) +& io.b(i)
    io.out(i) := Mux(sum >= modulus.U, sum - modulus.U, sum)
  }
}

/**
  * Polynomial Subtractor
  * 
  * Subtracts two polynomials coefficient-wise modulo q.
  * c(X) = a(X) - b(X)
  * 
  * @param n Polynomial degree
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class PolynomialSubtractor(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val b = Input(Vec(n, UInt(width.W)))
    val out = Output(Vec(n, UInt(width.W)))
  })
  
  for (i <- 0 until n) {
    val diff = io.a(i).asSInt - io.b(i).asSInt
    io.out(i) := Mux(diff < 0.S, (diff + modulus.S).asUInt, diff.asUInt)
  }
}

/**
  * Polynomial Negation
  * 
  * Negates a polynomial coefficient-wise modulo q.
  * c(X) = -a(X)
  * 
  * @param n Polynomial degree
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class PolynomialNegator(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val out = Output(Vec(n, UInt(width.W)))
  })
  
  for (i <- 0 until n) {
    io.out(i) := Mux(io.a(i) === 0.U, 0.U, modulus.U - io.a(i))
  }
}

/**
  * Polynomial Scalar Multiplier
  * 
  * Multiplies a polynomial by a scalar modulo q.
  * c(X) = s * a(X)
  * 
  * @param n Polynomial degree
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class PolynomialScalarMult(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val scalar = Input(UInt(width.W))
    val out = Output(Vec(n, UInt(width.W)))
  })
  
  for (i <- 0 until n) {
    io.out(i) := ((io.a(i) * io.scalar) % modulus.U)(width - 1, 0)
  }
}

/**
  * Polynomial X^k Multiplier
  * 
  * Multiplies a polynomial by X^k in the ring Z_q[X]/(X^N + 1).
  * Since X^N ≡ -1, multiplication by X^k rotates and potentially negates.
  * 
  * For a(X) * X^k:
  *   - If k < N: rotate coefficients by k positions, negate wrapped
  *   - If k >= N: additional negations based on full rotations
  * 
  * @param n Polynomial degree (must be power of 2)
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class PolynomialXkMult(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val k = Input(UInt(log2Ceil(2 * n).W))  // k can be up to 2N-1
    val out = Output(Vec(n, UInt(width.W)))
  })
  
  val logN = log2Ceil(n)
  
  // Normalize k to [0, 2N) since X^{2N} = 1
  val kNorm = io.k(logN, 0)
  val fullRotation = io.k(logN)  // Whether k >= N (full rotation means extra negation)
  
  // Rotation amount within [0, N)
  val rotAmt = kNorm(logN - 1, 0)
  
  for (i <- 0 until n) {
    // Source index after rotation (going backwards)
    val srcIdx = Wire(UInt(logN.W))
    val needNegate = Wire(Bool())
    
    // i = srcIdx + k mod N, so srcIdx = i - k mod N
    val iMinusK = i.U.asSInt - rotAmt.asSInt
    
    when(iMinusK < 0.S) {
      // Wrapped around: coefficient comes from high indices and gets negated
      srcIdx := (i.U +& n.U - rotAmt)(logN - 1, 0)
      needNegate := true.B
    }.otherwise {
      srcIdx := iMinusK(logN - 1, 0).asUInt
      needNegate := false.B
    }
    
    // Additional negation if k >= N
    val totalNegate = needNegate ^ fullRotation
    
    // Select and potentially negate
    val selected = io.a(srcIdx)
    io.out(i) := Mux(totalNegate && selected =/= 0.U, 
                     modulus.U - selected, 
                     selected)
  }
}

/**
  * Polynomial Automorphism
  * 
  * Computes the automorphism a(X) -> a(X^k) for odd k.
  * This is used in key switching and rotation operations.
  * 
  * For the ring Z_q[X]/(X^N + 1), valid automorphisms are X -> X^k
  * where k is odd (since gcd(k, 2N) = 1 is required).
  * 
  * @param n Polynomial degree
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class PolynomialAutomorphism(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val k = Input(UInt(log2Ceil(2 * n).W))  // Automorphism index (must be odd)
    val out = Output(Vec(n, UInt(width.W)))
  })
  
  val logN = log2Ceil(n)
  val twoN = 2 * n
  
  // For each output coefficient at position i:
  // a(X^k)[i] comes from a[j] where i = j*k mod 2N
  // Need to solve: j = i * k^{-1} mod 2N
  
  for (i <- 0 until n) {
    // Since k is odd, we can compute k^{-1} mod 2N
    // For hardware, we precompute the mapping for each k
    
    // Simpler approach: check each source coefficient
    val sourceIdx = Wire(UInt(logN.W))
    val needNegate = Wire(Bool())
    
    // j*k mod 2N = i means j = i/k mod 2N
    // For odd k and power-of-2 N, k has a multiplicative inverse mod 2N
    
    // Compute source index: find j such that (j * k) mod 2N = i or = i + N
    val jTimesK = Wire(Vec(n, UInt((logN + 1).W)))
    for (j <- 0 until n) {
      jTimesK(j) := ((j.U * io.k) % twoN.U)(logN, 0)
    }
    
    // Find j that maps to i
    val found = Wire(Vec(n, Bool()))
    val fromHigh = Wire(Vec(n, Bool()))
    for (j <- 0 until n) {
      found(j) := jTimesK(j) === i.U || jTimesK(j) === (i + n).U
      fromHigh(j) := jTimesK(j) === (i + n).U
    }
    
    // Priority encoder to find the source
    val srcRaw = PriorityEncoder(found.asUInt)
    sourceIdx := srcRaw
    needNegate := fromHigh(srcRaw)
    
    val selected = io.a(sourceIdx)
    io.out(i) := Mux(needNegate && selected =/= 0.U,
                     modulus.U - selected,
                     selected)
  }
}

/**
  * Polynomial Register Bank
  * 
  * Stores multiple polynomials with read/write access.
  * Used for key storage and intermediate results.
  * 
  * @param numPolys Number of polynomials to store
  * @param n Polynomial degree
  * @param width Coefficient bit width
  */
class PolynomialRegBank(numPolys: Int, n: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    // Read port
    val rdAddr = Input(UInt(log2Ceil(numPolys).W))
    val rdData = Output(Vec(n, UInt(width.W)))
    
    // Write port
    val wrAddr = Input(UInt(log2Ceil(numPolys).W))
    val wrData = Input(Vec(n, UInt(width.W)))
    val wrEn = Input(Bool())
  })
  
  val bank = Reg(Vec(numPolys, Vec(n, UInt(width.W))))
  
  io.rdData := bank(io.rdAddr)
  
  when(io.wrEn) {
    bank(io.wrAddr) := io.wrData
  }
}

/**
  * Polynomial Memory Interface
  * 
  * Provides a memory-mapped interface for polynomial storage.
  * Supports streaming coefficient access for efficient data transfer.
  * 
  * @param n Polynomial degree
  * @param width Coefficient bit width
  * @param depth Number of polynomials in memory
  */
class PolynomialMemory(n: Int, width: Int, depth: Int) extends Module {
  val io = IO(new Bundle {
    // Polynomial-level interface
    val polyAddr = Input(UInt(log2Ceil(depth).W))
    val polyRdData = Output(Vec(n, UInt(width.W)))
    val polyWrData = Input(Vec(n, UInt(width.W)))
    val polyWrEn = Input(Bool())
    
    // Coefficient-level streaming interface
    val coefAddr = Input(UInt(log2Ceil(n).W))
    val coefRdData = Output(UInt(width.W))
    val coefWrData = Input(UInt(width.W))
    val coefWrEn = Input(Bool())
    
    // Control
    val useCoefInterface = Input(Bool())
  })
  
  // Underlying memory
  val mem = SyncReadMem(depth * n, UInt(width.W))
  
  // Polynomial-level access
  val baseAddr = io.polyAddr * n.U
  
  when(!io.useCoefInterface) {
    // Full polynomial read (multi-cycle in practice)
    for (i <- 0 until n) {
      io.polyRdData(i) := mem.read(baseAddr + i.U)
    }
    
    when(io.polyWrEn) {
      for (i <- 0 until n) {
        mem.write(baseAddr + i.U, io.polyWrData(i))
      }
    }
  }.otherwise {
    // Coefficient-level streaming access
    io.polyRdData := VecInit(Seq.fill(n)(0.U(width.W)))
    
    val coefFullAddr = baseAddr + io.coefAddr
    io.coefRdData := mem.read(coefFullAddr)
    
    when(io.coefWrEn) {
      mem.write(coefFullAddr, io.coefWrData)
    }
  }
  
  when(io.useCoefInterface) {
    io.coefRdData := mem.read(baseAddr + io.coefAddr)
  }.otherwise {
    io.coefRdData := 0.U
  }
}

/**
  * Polynomial ALU
  * 
  * Unified arithmetic logic unit for polynomial operations.
  * Supports add, subtract, negate, and scalar multiply operations.
  * 
  * @param n Polynomial degree
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class PolynomialALU(n: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(n, UInt(width.W)))
    val b = Input(Vec(n, UInt(width.W)))
    val scalar = Input(UInt(width.W))
    val op = Input(UInt(3.W))  // 0: add, 1: sub, 2: neg, 3: scalar mul, 4: xk mul
    val k = Input(UInt(log2Ceil(2 * n).W))  // For X^k multiplication
    val out = Output(Vec(n, UInt(width.W)))
  })
  
  // Sub-modules
  val adder = Module(new PolynomialAdder(n, width, modulus))
  val subtractor = Module(new PolynomialSubtractor(n, width, modulus))
  val negator = Module(new PolynomialNegator(n, width, modulus))
  val scalarMult = Module(new PolynomialScalarMult(n, width, modulus))
  val xkMult = Module(new PolynomialXkMult(n, width, modulus))
  
  // Connect inputs
  adder.io.a := io.a
  adder.io.b := io.b
  
  subtractor.io.a := io.a
  subtractor.io.b := io.b
  
  negator.io.a := io.a
  
  scalarMult.io.a := io.a
  scalarMult.io.scalar := io.scalar
  
  xkMult.io.a := io.a
  xkMult.io.k := io.k
  
  // Output mux
  io.out := MuxLookup(io.op, adder.io.out, Seq(
    0.U -> adder.io.out,
    1.U -> subtractor.io.out,
    2.U -> negator.io.out,
    3.U -> scalarMult.io.out,
    4.U -> xkMult.io.out
  ))
}

object PolynomialUnitMain extends App {
  emitVerilog(new PolynomialALU(256, 13, 7681), Array("--target-dir", "generated/fhe"))
  emitVerilog(new PolynomialXkMult(256, 13, 7681), Array("--target-dir", "generated/fhe"))
}
