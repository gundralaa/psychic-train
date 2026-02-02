package fhe

import chisel3._
import chisel3.util._

/**
  * Configuration object for FHE parameters.
  * 
  * These parameters define the security level and performance characteristics
  * of the FHE scheme. Based on TFHE-like constructions.
  */
object FHEParams {
  // LWE parameters
  val n = 512                    // LWE dimension (security parameter)
  val q = 1024                   // Ciphertext modulus (power of 2 for efficiency)
  val logQ = 10                  // log2(q)
  
  // RLWE parameters for bootstrapping
  val N = 1024                   // Ring dimension (power of 2)
  val logN = 10                  // log2(N)
  val bigQ = 4096                // Larger modulus for RLWE
  val logBigQ = 12               // log2(bigQ)
  
  // Gadget decomposition parameters
  val baseG = 4                  // Gadget base
  val logBaseG = 2               // log2(baseG)
  val ellG = 6                   // Number of gadget digits (logBigQ / logBaseG)
  
  // Noise parameters
  val stdDev = 3.2               // Standard deviation for noise
  val noiseWidth = 8             // Bit width for noise values
  
  // Torus encoding
  val torusBits = 32             // Bit width for torus representation
}

/**
  * Modular Adder
  * 
  * Performs addition modulo q using reduction.
  * For power-of-2 modulus, this is simply bit truncation.
  * 
  * @param width Bit width of operands
  * @param modulus The modulus value (should be power of 2 for efficiency)
  */
class ModularAdder(width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val out = Output(UInt(width.W))
  })
  
  val sum = io.a +& io.b  // Use +& to get extra bit for overflow
  
  if (isPow2(modulus)) {
    // For power-of-2 modulus, just truncate
    io.out := sum(width - 1, 0)
  } else {
    // General case: conditional subtraction
    io.out := Mux(sum >= modulus.U, sum - modulus.U, sum)
  }
  
  private def isPow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
}

/**
  * Modular Subtractor
  * 
  * Performs subtraction modulo q.
  * 
  * @param width Bit width of operands
  * @param modulus The modulus value
  */
class ModularSubtractor(width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val out = Output(UInt(width.W))
  })
  
  val diff = io.a.asSInt - io.b.asSInt
  
  if (isPow2(modulus)) {
    io.out := diff(width - 1, 0).asUInt
  } else {
    io.out := Mux(diff < 0.S, (diff + modulus.S).asUInt, diff.asUInt)
  }
  
  private def isPow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
}

/**
  * Modular Multiplier
  * 
  * Performs multiplication modulo q using various reduction strategies.
  * 
  * @param width Bit width of operands
  * @param modulus The modulus value
  */
class ModularMultiplier(width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val valid = Input(Bool())
    val out = Output(UInt(width.W))
    val done = Output(Bool())
  })
  
  val product = io.a * io.b
  
  if (isPow2(modulus)) {
    // For power-of-2 modulus, just truncate to width bits
    io.out := product(width - 1, 0)
    io.done := io.valid
  } else {
    // Barrett reduction for non-power-of-2 modulus
    // This is a simplified version; full Barrett would need precomputed constant
    val reduced = product % modulus.U
    io.out := reduced(width - 1, 0)
    io.done := io.valid
  }
  
  private def isPow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
}

/**
  * Montgomery Multiplier
  * 
  * Efficient modular multiplication using Montgomery reduction.
  * Operates in Montgomery domain for chained multiplications.
  * 
  * @param width Bit width of operands
  * @param modulus The modulus value (must be odd)
  */
class MontgomeryMultiplier(width: Int, modulus: Int) extends Module {
  require(modulus % 2 == 1, "Montgomery reduction requires odd modulus")
  
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))       // In Montgomery form
    val b = Input(UInt(width.W))       // In Montgomery form
    val start = Input(Bool())
    val out = Output(UInt(width.W))    // In Montgomery form
    val done = Output(Bool())
  })
  
  // Precomputed constants
  val R = 1 << width                    // R = 2^width
  val RMod = (R % modulus).U           // R mod N
  
  // Compute -N^(-1) mod R (precomputed at compile time)
  val nPrime = computeNPrime(modulus, width)
  
  // Pipeline stages
  val state = RegInit(0.U(2.W))
  val product = Reg(UInt((2 * width).W))
  val m = Reg(UInt(width.W))
  val t = Reg(UInt((2 * width + 1).W))
  val result = Reg(UInt(width.W))
  
  io.done := false.B
  io.out := result
  
  switch(state) {
    is(0.U) {
      when(io.start) {
        product := io.a * io.b
        state := 1.U
      }
    }
    is(1.U) {
      m := (product(width - 1, 0) * nPrime.U)(width - 1, 0)
      state := 2.U
    }
    is(2.U) {
      t := product + (m * modulus.U)
      state := 3.U
    }
    is(3.U) {
      val tHigh = t(2 * width, width)
      result := Mux(tHigh >= modulus.U, tHigh - modulus.U, tHigh)
      io.done := true.B
      state := 0.U
    }
  }
  
  // Extended GCD to find modular inverse
  private def computeNPrime(n: Int, bits: Int): Int = {
    val r = 1 << bits
    // Find -n^(-1) mod r using extended Euclidean algorithm
    var t1 = 0
    var t2 = 1
    var r1 = r
    var r2 = n
    while (r2 != 0) {
      val q = r1 / r2
      val temp = t1 - q * t2
      t1 = t2
      t2 = temp
      val rTemp = r1 - q * r2
      r1 = r2
      r2 = rTemp
    }
    val nInv = if (t1 < 0) t1 + r else t1
    (r - nInv) & ((1 << bits) - 1)
  }
}

/**
  * Modular Reduction Unit
  * 
  * Reduces a value modulo q using various strategies based on modulus properties.
  * 
  * @param inputWidth Width of input value
  * @param outputWidth Width of output value
  * @param modulus The modulus for reduction
  */
class ModularReduction(inputWidth: Int, outputWidth: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(inputWidth.W))
    val out = Output(UInt(outputWidth.W))
  })
  
  if (isPow2(modulus)) {
    // Simple truncation for power-of-2 modulus
    val logMod = log2Ceil(modulus)
    io.out := io.in(logMod - 1, 0)
  } else {
    // General modular reduction
    io.out := (io.in % modulus.U)(outputWidth - 1, 0)
  }
  
  private def isPow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
}

/**
  * Torus Arithmetic Unit
  * 
  * In TFHE, ciphertexts live on the torus T = R/Z, represented as
  * fixed-point numbers in [0, 1). We use 32-bit integers where the
  * value represents a fraction: x/2^32.
  * 
  * Addition and subtraction on the torus wrap around naturally
  * using unsigned arithmetic.
  * 
  * @param width Bit width of torus elements
  */
class TorusArithmetic(width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(width.W))
    val b = Input(UInt(width.W))
    val op = Input(UInt(2.W))  // 0: add, 1: sub, 2: scale
    val scalar = Input(SInt(16.W))  // For scaling operation
    val out = Output(UInt(width.W))
  })
  
  val result = Wire(UInt(width.W))
  
  switch(io.op) {
    is(0.U) {
      // Addition: wraps naturally due to unsigned overflow
      result := io.a + io.b
    }
    is(1.U) {
      // Subtraction: wraps naturally due to unsigned underflow
      result := io.a - io.b
    }
    is(2.U) {
      // Scalar multiplication
      val scaledSigned = io.a.asSInt * io.scalar
      result := scaledSigned.asUInt
    }
  }
  
  io.out := result
}

/**
  * Gadget Decomposition Unit
  * 
  * Decomposes a torus element into digits in base Bg.
  * This is crucial for external product operations in TFHE.
  * 
  * For a value a and base Bg with ell digits, outputs:
  *   a = sum_{i=0}^{ell-1} a_i * Bg^i
  * where each a_i is in [-Bg/2, Bg/2)
  * 
  * @param width Input bit width
  * @param base Gadget base (Bg)
  * @param numDigits Number of digits in decomposition (ell)
  */
class GadgetDecomposition(width: Int, base: Int, numDigits: Int) extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(width.W))
    val digits = Output(Vec(numDigits, SInt(log2Ceil(base + 1).W)))
  })
  
  val logBase = log2Ceil(base)
  val halfBase = base / 2
  
  // Extract digits from MSB to LSB
  val shifted = Wire(Vec(numDigits, UInt(width.W)))
  val rawDigits = Wire(Vec(numDigits, UInt(logBase.W)))
  val signedDigits = Wire(Vec(numDigits, SInt((logBase + 1).W)))
  
  // Initial value
  shifted(numDigits - 1) := io.in >> (width - logBase * numDigits)
  
  for (i <- (numDigits - 1) to 0 by -1) {
    rawDigits(i) := shifted(i)(logBase - 1, 0)
    
    // Convert to signed representation in [-Bg/2, Bg/2)
    val digit = rawDigits(i)
    val carry = digit >= halfBase.U
    
    when(carry) {
      signedDigits(i) := (digit.asSInt - base.S)
    }.otherwise {
      signedDigits(i) := digit.asSInt
    }
    
    // Propagate carry for next iteration
    if (i > 0) {
      shifted(i - 1) := shifted(i) >> logBase
      when(carry) {
        shifted(i - 1) := (shifted(i) >> logBase) + 1.U
      }
    }
    
    io.digits(i) := signedDigits(i)
  }
}

/**
  * Vector-Scalar Modular Multiply-Accumulate
  * 
  * Computes: result = sum(a_i * b_i) mod q
  * Used in inner product computation for LWE operations.
  * 
  * @param vecSize Size of input vectors
  * @param elemWidth Bit width of vector elements
  * @param modulus The modulus for reduction
  */
class VectorMAC(vecSize: Int, elemWidth: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(vecSize, UInt(elemWidth.W)))
    val b = Input(Vec(vecSize, UInt(elemWidth.W)))
    val start = Input(Bool())
    val result = Output(UInt(elemWidth.W))
    val done = Output(Bool())
  })
  
  val accWidth = elemWidth + log2Ceil(vecSize) + 1
  val accumulator = Reg(UInt(accWidth.W))
  val counter = RegInit(0.U(log2Ceil(vecSize + 1).W))
  val busy = RegInit(false.B)
  
  io.done := false.B
  io.result := Mux(isPow2(modulus), 
    accumulator(elemWidth - 1, 0),
    (accumulator % modulus.U)(elemWidth - 1, 0))
  
  when(io.start && !busy) {
    accumulator := 0.U
    counter := 0.U
    busy := true.B
  }
  
  when(busy) {
    when(counter < vecSize.U) {
      val product = io.a(counter) * io.b(counter)
      accumulator := accumulator + product
      counter := counter + 1.U
    }.otherwise {
      busy := false.B
      io.done := true.B
    }
  }
  
  private def isPow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
}

object ModularArithmeticMain extends App {
  emitVerilog(new ModularAdder(10, 1024), Array("--target-dir", "generated/fhe"))
  emitVerilog(new ModularMultiplier(10, 1024), Array("--target-dir", "generated/fhe"))
  emitVerilog(new TorusArithmetic(32), Array("--target-dir", "generated/fhe"))
  emitVerilog(new GadgetDecomposition(32, 4, 6), Array("--target-dir", "generated/fhe"))
}
