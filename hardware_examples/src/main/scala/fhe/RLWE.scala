package fhe

import chisel3._
import chisel3.util._

/**
  * Ring Learning With Errors (RLWE) Operations
  * 
  * RLWE works over the polynomial ring R_q = Z_q[X]/(X^N + 1).
  * An RLWE sample is (a(X), b(X)) where:
  *   - a(X) is a random polynomial
  *   - b(X) = a(X) * s(X) + e(X) + m(X) * Δ
  *   - s(X) is the secret key polynomial
  *   - e(X) is a small error polynomial
  *   - m(X) is the message polynomial
  * 
  * RLWE is more efficient than LWE for FHE because polynomial operations
  * can be performed using NTT in O(N log N) time.
  */

/**
  * RLWE Ciphertext
  * 
  * Represents an RLWE encryption: (a(X), b(X)) ∈ R_q × R_q
  */
class RLWECiphertext(N: Int, width: Int) extends Bundle {
  val a = Vec(N, UInt(width.W))
  val b = Vec(N, UInt(width.W))
}

/**
  * RGSW Ciphertext
  * 
  * Ring-GSW ciphertext used for external products in bootstrapping.
  * Contains multiple RLWE ciphertexts arranged in a gadget matrix structure.
  * 
  * RGSW(m) = (RLWE(m * g_1), RLWE(m * g_2), ..., RLWE(m * g_ell))
  * where g_i are gadget decomposition powers.
  */
class RGSWCiphertext(N: Int, width: Int, ell: Int) extends Bundle {
  // Each row is an RLWE ciphertext encrypting m * B^i
  val rows = Vec(2 * ell, new RLWECiphertext(N, width))
}

/**
  * RLWE Encryption Unit
  * 
  * Encrypts a polynomial message m(X) as:
  *   a(X) ← random
  *   b(X) = a(X) * s(X) + e(X) + m(X) * Δ mod (X^N + 1, q)
  * 
  * @param N Ring dimension
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class RLWEEncrypt(N: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(Vec(N, UInt(width.W)))           // Random polynomial
    val secretKey = Input(Vec(N, UInt(width.W)))   // Secret key s(X)
    val noise = Input(Vec(N, UInt(width.W)))       // Error polynomial e(X)
    val message = Input(Vec(N, UInt(width.W)))     // Plaintext m(X)
    val delta = Input(UInt(width.W))               // Message encoding factor Δ
    val twiddles = Input(Vec(N, UInt(width.W)))    // NTT twiddle factors
    val invTwiddles = Input(Vec(N, UInt(width.W))) // Inverse NTT twiddles
    val start = Input(Bool())
    val ciphertext = Output(new RLWECiphertext(N, width))
    val done = Output(Bool())
  })
  
  // NTT-based polynomial multiplier for a * s
  val polyMult = Module(new NTTPolynomialMultiplier(N, width, modulus))
  polyMult.io.a := io.a
  polyMult.io.b := io.secretKey
  polyMult.io.twiddles := io.twiddles
  polyMult.io.invTwiddles := io.invTwiddles
  
  // State machine
  val sIdle :: sMultiply :: sFinalize :: sDone = Enum(4)
  val state = RegInit(sIdle)
  
  val resultReg = Reg(new RLWECiphertext(N, width))
  
  polyMult.io.start := (state === sIdle) && io.start
  
  io.done := (state === sDone)
  io.ciphertext := resultReg
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        resultReg.a := io.a
        state := sMultiply
      }
    }
    is(sMultiply) {
      when(polyMult.io.done) {
        state := sFinalize
      }
    }
    is(sFinalize) {
      // b = a*s + e + m*Δ mod q
      for (i <- 0 until N) {
        val as = polyMult.io.out(i)
        val msgScaled = (io.message(i) * io.delta) % modulus.U
        val sum = as + io.noise(i) + msgScaled
        resultReg.b(i) := Mux(sum >= modulus.U, 
                              Mux(sum >= (2 * modulus).U, sum - (2 * modulus).U, sum - modulus.U),
                              sum)
      }
      state := sDone
    }
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * RLWE Decryption Unit
  * 
  * Decrypts an RLWE ciphertext (a, b) by computing:
  *   m'(X) = b(X) - a(X) * s(X) mod (X^N + 1, q)
  * Then decodes by rounding coefficients.
  * 
  * @param N Ring dimension
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class RLWEDecrypt(N: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val ciphertext = Input(new RLWECiphertext(N, width))
    val secretKey = Input(Vec(N, UInt(width.W)))
    val twiddles = Input(Vec(N, UInt(width.W)))
    val invTwiddles = Input(Vec(N, UInt(width.W)))
    val start = Input(Bool())
    val plaintext = Output(Vec(N, UInt(width.W)))
    val done = Output(Bool())
  })
  
  // Polynomial multiplier for a * s
  val polyMult = Module(new NTTPolynomialMultiplier(N, width, modulus))
  polyMult.io.a := io.ciphertext.a
  polyMult.io.b := io.secretKey
  polyMult.io.twiddles := io.twiddles
  polyMult.io.invTwiddles := io.invTwiddles
  
  // State machine
  val sIdle :: sMultiply :: sSubtract :: sDone = Enum(4)
  val state = RegInit(sIdle)
  
  val resultReg = Reg(Vec(N, UInt(width.W)))
  
  polyMult.io.start := (state === sIdle) && io.start
  
  io.done := (state === sDone)
  io.plaintext := resultReg
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sMultiply
      }
    }
    is(sMultiply) {
      when(polyMult.io.done) {
        state := sSubtract
      }
    }
    is(sSubtract) {
      // phase = b - a*s mod q
      for (i <- 0 until N) {
        val diff = io.ciphertext.b(i).asSInt - polyMult.io.out(i).asSInt
        resultReg(i) := Mux(diff < 0.S, (diff + modulus.S).asUInt, diff.asUInt)
      }
      state := sDone
    }
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * RLWE Ciphertext Addition
  * 
  * Homomorphically adds two RLWE ciphertexts:
  *   (a1, b1) + (a2, b2) = (a1 + a2, b1 + b2) mod q
  * 
  * @param N Ring dimension
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class RLWEAdd(N: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val ct1 = Input(new RLWECiphertext(N, width))
    val ct2 = Input(new RLWECiphertext(N, width))
    val result = Output(new RLWECiphertext(N, width))
  })
  
  for (i <- 0 until N) {
    val sumA = io.ct1.a(i) +& io.ct2.a(i)
    io.result.a(i) := Mux(sumA >= modulus.U, sumA - modulus.U, sumA)
    
    val sumB = io.ct1.b(i) +& io.ct2.b(i)
    io.result.b(i) := Mux(sumB >= modulus.U, sumB - modulus.U, sumB)
  }
}

/**
  * RLWE Ciphertext Subtraction
  * 
  * Homomorphically subtracts RLWE ciphertexts:
  *   (a1, b1) - (a2, b2) = (a1 - a2, b1 - b2) mod q
  * 
  * @param N Ring dimension
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class RLWESubtract(N: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val ct1 = Input(new RLWECiphertext(N, width))
    val ct2 = Input(new RLWECiphertext(N, width))
    val result = Output(new RLWECiphertext(N, width))
  })
  
  for (i <- 0 until N) {
    val diffA = io.ct1.a(i).asSInt - io.ct2.a(i).asSInt
    io.result.a(i) := Mux(diffA < 0.S, (diffA + modulus.S).asUInt, diffA.asUInt)
    
    val diffB = io.ct1.b(i).asSInt - io.ct2.b(i).asSInt
    io.result.b(i) := Mux(diffB < 0.S, (diffB + modulus.S).asUInt, diffB.asUInt)
  }
}

/**
  * RLWE External Product
  * 
  * Computes the external product: RLWE ⊡ RGSW
  * 
  * This is a key operation in TFHE bootstrapping.
  * Given RLWE ciphertext c = (a, b) and RGSW ciphertext C = RGSW(m'),
  * computes an RLWE ciphertext encrypting m * m' where m is the
  * message in the RLWE ciphertext.
  * 
  * Algorithm:
  *   1. Decompose a and b into digits: a = Σ a_i * B^i, b = Σ b_i * B^i
  *   2. result = Σ_i (a_i * C[i] + b_i * C[ell + i])
  * 
  * @param N Ring dimension
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  * @param base Decomposition base B
  * @param ell Number of decomposition digits
  */
class RLWEExternalProduct(N: Int, width: Int, modulus: Int, base: Int, ell: Int) extends Module {
  val io = IO(new Bundle {
    val rlwe = Input(new RLWECiphertext(N, width))
    val rgsw = Input(new RGSWCiphertext(N, width, ell))
    val twiddles = Input(Vec(N, UInt(width.W)))
    val invTwiddles = Input(Vec(N, UInt(width.W)))
    val start = Input(Bool())
    val result = Output(new RLWECiphertext(N, width))
    val done = Output(Bool())
  })
  
  val logBase = log2Ceil(base)
  
  // State machine
  val sIdle :: sDecompose :: sMultAcc :: sFinalize :: sDone = Enum(5)
  val state = RegInit(sIdle)
  
  // Decomposed digits for a and b
  val aDigits = Reg(Vec(ell, Vec(N, SInt((logBase + 1).W))))
  val bDigits = Reg(Vec(ell, Vec(N, SInt((logBase + 1).W))))
  
  // Accumulators
  val accA = Reg(Vec(N, UInt(width.W)))
  val accB = Reg(Vec(N, UInt(width.W)))
  
  // Counters
  val digitCounter = RegInit(0.U(log2Ceil(ell + 1).W))
  val phase = RegInit(0.U(1.W))  // 0: process a digits, 1: process b digits
  
  // Polynomial multiplier
  val polyMult = Module(new NTTPolynomialMultiplier(N, width, modulus))
  
  io.done := (state === sDone)
  io.result.a := accA
  io.result.b := accB
  
  // Default connections
  polyMult.io.twiddles := io.twiddles
  polyMult.io.invTwiddles := io.invTwiddles
  polyMult.io.start := false.B
  polyMult.io.a := VecInit(Seq.fill(N)(0.U(width.W)))
  polyMult.io.b := VecInit(Seq.fill(N)(0.U(width.W)))
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        // Initialize accumulators to zero
        for (i <- 0 until N) {
          accA(i) := 0.U
          accB(i) := 0.U
        }
        digitCounter := 0.U
        phase := 0.U
        state := sDecompose
      }
    }
    
    is(sDecompose) {
      // Signed digit decomposition of a and b
      for (i <- 0 until N) {
        var carryA = 0.S(2.W)
        var carryB = 0.S(2.W)
        val aVal = io.rlwe.a(i)
        val bVal = io.rlwe.b(i)
        
        for (j <- 0 until ell) {
          // Extract digit j
          val aDigit = (aVal >> (j * logBase))(logBase - 1, 0)
          val bDigit = (bVal >> (j * logBase))(logBase - 1, 0)
          
          // Convert to signed digit in [-B/2, B/2)
          val halfBase = (base / 2).U
          val signedADigit = Mux(aDigit >= halfBase, 
                                  aDigit.asSInt - base.S, 
                                  aDigit.asSInt)
          val signedBDigit = Mux(bDigit >= halfBase,
                                  bDigit.asSInt - base.S,
                                  bDigit.asSInt)
          
          aDigits(j)(i) := signedADigit
          bDigits(j)(i) := signedBDigit
        }
      }
      state := sMultAcc
    }
    
    is(sMultAcc) {
      // Multiply digits by RGSW rows and accumulate
      // This is simplified; full implementation would need more cycles
      
      val rowIdx = Mux(phase === 0.U, digitCounter, digitCounter + ell.U)
      val currentDigits = Mux(phase === 0.U, aDigits(digitCounter), bDigits(digitCounter))
      
      // Convert signed digits to polynomials for multiplication
      val digitPoly = Wire(Vec(N, UInt(width.W)))
      for (i <- 0 until N) {
        val d = currentDigits(i)
        digitPoly(i) := Mux(d < 0.S, (d + modulus.S).asUInt, d.asUInt)
      }
      
      // Multiply digit polynomial by RGSW row
      polyMult.io.a := digitPoly
      polyMult.io.b := io.rgsw.rows(rowIdx).a
      polyMult.io.start := RegNext(state === sMultAcc && !polyMult.io.done)
      
      when(polyMult.io.done) {
        // Accumulate result
        for (i <- 0 until N) {
          val sumA = accA(i) + polyMult.io.out(i)
          accA(i) := Mux(sumA >= modulus.U, sumA - modulus.U, sumA)
        }
        
        // Move to next digit or next phase
        when(digitCounter === (ell - 1).U) {
          digitCounter := 0.U
          when(phase === 0.U) {
            phase := 1.U
          }.otherwise {
            state := sFinalize
          }
        }.otherwise {
          digitCounter := digitCounter + 1.U
        }
      }
    }
    
    is(sFinalize) {
      state := sDone
    }
    
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * RLWE X^k Multiplication
  * 
  * Multiplies an RLWE ciphertext by X^k, which rotates the plaintext.
  * In Z_q[X]/(X^N + 1), X^N = -1, so rotation wraps with negation.
  * 
  * @param N Ring dimension
  * @param width Coefficient bit width
  * @param modulus Prime modulus q
  */
class RLWEXkMult(N: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val ct = Input(new RLWECiphertext(N, width))
    val k = Input(UInt(log2Ceil(2 * N).W))
    val result = Output(new RLWECiphertext(N, width))
  })
  
  // Polynomial X^k multipliers
  val xkMultA = Module(new PolynomialXkMult(N, width, modulus))
  val xkMultB = Module(new PolynomialXkMult(N, width, modulus))
  
  xkMultA.io.a := io.ct.a
  xkMultA.io.k := io.k
  
  xkMultB.io.a := io.ct.b
  xkMultB.io.k := io.k
  
  io.result.a := xkMultA.io.out
  io.result.b := xkMultB.io.out
}

/**
  * Trivial RLWE Encryption
  * 
  * Creates a trivial (noiseless) RLWE encryption:
  *   (0, m(X)) encrypts m(X) under any key
  * 
  * @param N Ring dimension
  * @param width Coefficient bit width
  */
class TrivialRLWEEncrypt(N: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val message = Input(Vec(N, UInt(width.W)))
    val ciphertext = Output(new RLWECiphertext(N, width))
  })
  
  for (i <- 0 until N) {
    io.ciphertext.a(i) := 0.U
    io.ciphertext.b(i) := io.message(i)
  }
}

object RLWEMain extends App {
  val N = 256
  val width = 13
  val q = 7681
  
  emitVerilog(new RLWEAdd(N, width, q), Array("--target-dir", "generated/fhe"))
  emitVerilog(new RLWEXkMult(N, width, q), Array("--target-dir", "generated/fhe"))
}
