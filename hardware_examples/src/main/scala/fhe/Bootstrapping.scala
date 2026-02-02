package fhe

import chisel3._
import chisel3.util._

/**
  * Bootstrapping for Fully Homomorphic Encryption
  * 
  * Bootstrapping is the key operation that makes FHE "fully" homomorphic.
  * It refreshes a ciphertext by homomorphically evaluating the decryption
  * circuit, reducing the noise accumulated from prior operations.
  * 
  * TFHE Bootstrapping Algorithm:
  * 1. Start with LWE ciphertext (a, b) under key s
  * 2. Create accumulator ACC = X^{b'} * v(X) where v(X) encodes the lookup table
  * 3. For each bit of the key: ACC = ACC * BSK[i] if a[i] = 1 (blind rotation)
  * 4. Extract LWE sample from ACC
  * 5. Key switch back to original key format
  * 
  * The bootstrapping key (BSK) is a set of RGSW encryptions of the secret key bits.
  */

/**
  * Bootstrapping Key
  * 
  * Contains RGSW encryptions of each secret key bit.
  * BSK[i] = RGSW(s[i]) for i = 0 to n-1
  */
class BootstrappingKey(n: Int, N: Int, width: Int, ell: Int) extends Bundle {
  val keys = Vec(n, new RGSWCiphertext(N, width, ell))
}

/**
  * Blind Rotation Unit
  * 
  * The core of TFHE bootstrapping. Performs the blind rotation:
  *   ACC' = ACC * X^{-a·s}
  * 
  * This is computed as:
  *   for i in 0..n-1:
  *     ACC = CMux(s[i], ACC * X^{-a[i]}, ACC)
  * 
  * where CMux(b, c0, c1) = c1 if b=1 else c0, computed using external product.
  * 
  * @param n LWE dimension
  * @param N Ring dimension
  * @param width Bit width
  * @param modulus Prime modulus
  * @param ell RGSW decomposition length
  */
class BlindRotation(n: Int, N: Int, width: Int, modulus: Int, ell: Int) extends Module {
  val io = IO(new Bundle {
    val acc = Input(new RLWECiphertext(N, width))
    val lweA = Input(Vec(n, UInt(width.W)))
    val bsk = Input(new BootstrappingKey(n, N, width, ell))
    val twiddles = Input(Vec(N, UInt(width.W)))
    val invTwiddles = Input(Vec(N, UInt(width.W)))
    val start = Input(Bool())
    val result = Output(new RLWECiphertext(N, width))
    val done = Output(Bool())
  })
  
  // State machine
  val sIdle :: sRotate :: sCMux :: sDone = Enum(4)
  val state = RegInit(sIdle)
  
  // Current accumulator
  val accReg = Reg(new RLWECiphertext(N, width))
  
  // Iteration counter
  val counter = RegInit(0.U(log2Ceil(n + 1).W))
  
  // Sub-modules
  val xkMult = Module(new RLWEXkMult(N, width, modulus))
  val extProd = Module(new RLWEExternalProduct(N, width, modulus, 4, ell))
  
  // Rotated accumulator
  val rotatedAcc = Reg(new RLWECiphertext(N, width))
  
  io.done := (state === sDone)
  io.result := accReg
  
  // Default connections
  xkMult.io.ct := accReg
  xkMult.io.k := 0.U
  
  extProd.io.rlwe := Reg(new RLWECiphertext(N, width))
  extProd.io.rgsw := io.bsk.keys(0)
  extProd.io.twiddles := io.twiddles
  extProd.io.invTwiddles := io.invTwiddles
  extProd.io.start := false.B
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        accReg := io.acc
        counter := 0.U
        state := sRotate
      }
    }
    
    is(sRotate) {
      // Compute rotation amount from LWE coefficient
      // k = round(a[i] * N / q) mod 2N
      val aScaled = (io.lweA(counter) * N.U) / modulus.U
      val rotAmount = (2 * N).U - aScaled  // Negative rotation
      
      xkMult.io.ct := accReg
      xkMult.io.k := rotAmount
      rotatedAcc := xkMult.io.result
      
      state := sCMux
    }
    
    is(sCMux) {
      // CMux: if s[i] = 1, use rotatedAcc; else use accReg
      // CMux(s[i], acc, rotatedAcc) = acc + s[i] * (rotatedAcc - acc)
      // Implemented via external product with BSK[i]
      
      // Compute diff = rotatedAcc - accReg
      val diffA = Wire(Vec(N, UInt(width.W)))
      val diffB = Wire(Vec(N, UInt(width.W)))
      
      for (j <- 0 until N) {
        val dA = rotatedAcc.a(j).asSInt - accReg.a(j).asSInt
        diffA(j) := Mux(dA < 0.S, (dA + modulus.S).asUInt, dA.asUInt)
        
        val dB = rotatedAcc.b(j).asSInt - accReg.b(j).asSInt
        diffB(j) := Mux(dB < 0.S, (dB + modulus.S).asUInt, dB.asUInt)
      }
      
      // External product: s[i] * diff
      val diffRLWE = Wire(new RLWECiphertext(N, width))
      diffRLWE.a := diffA
      diffRLWE.b := diffB
      
      extProd.io.rlwe := diffRLWE
      extProd.io.rgsw := io.bsk.keys(counter)
      extProd.io.start := RegNext(state === sCMux && !extProd.io.done)
      
      when(extProd.io.done) {
        // acc = acc + extProd.result
        for (j <- 0 until N) {
          val sumA = accReg.a(j) +& extProd.io.result.a(j)
          accReg.a(j) := Mux(sumA >= modulus.U, sumA - modulus.U, sumA)
          
          val sumB = accReg.b(j) +& extProd.io.result.b(j)
          accReg.b(j) := Mux(sumB >= modulus.U, sumB - modulus.U, sumB)
        }
        
        when(counter === (n - 1).U) {
          state := sDone
        }.otherwise {
          counter := counter + 1.U
          state := sRotate
        }
      }
    }
    
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * Test Polynomial Generator
  * 
  * Generates the test polynomial v(X) for bootstrapping.
  * This encodes the lookup table for the desired function.
  * 
  * For standard bootstrapping (sign function):
  *   v(X) = Σ_{j=0}^{N-1} v_j * X^j
  * where v_j encodes the function output for that rotation.
  * 
  * For NAND gate: v(X) is set so that blind rotation + sample extract
  * gives the NAND of the input bits.
  * 
  * @param N Ring dimension
  * @param width Coefficient bit width
  * @param modulus Prime modulus
  */
class TestPolynomialGen(N: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    val gateType = Input(UInt(4.W))  // Type of gate to implement
    val testPoly = Output(Vec(N, UInt(width.W)))
  })
  
  // For a generic bootstrapping, the test polynomial encodes:
  // - First half (rotations 0 to N/2-1): encode 0
  // - Second half (rotations N/2 to N-1): encode 1/2
  
  // This implements the sign function / threshold
  val halfQ = (modulus / 2).U(width.W)
  val quarterQ = (modulus / 4).U(width.W)
  
  // Standard sign-function test vector
  val signTestVec = Wire(Vec(N, UInt(width.W)))
  for (i <- 0 until N) {
    if (i < N / 2) {
      signTestVec(i) := quarterQ  // Encode 0 with positive offset
    } else {
      signTestVec(i) := (modulus - modulus/4).U  // Encode 1 with negative offset
    }
  }
  
  // Different test vectors for different gates can be added here
  io.testPoly := signTestVec
}

/**
  * Sample Extract with Accumulator Init
  * 
  * Initializes the accumulator for blind rotation and extracts
  * the final LWE sample after bootstrapping.
  * 
  * @param N Ring dimension
  * @param width Bit width
  * @param modulus Prime modulus
  */
class BootstrapAccumulator(N: Int, width: Int, modulus: Int) extends Module {
  val io = IO(new Bundle {
    // Initialization
    val initPhase = Input(UInt(width.W))  // Initial rotation from LWE.b
    val testPoly = Input(Vec(N, UInt(width.W)))
    val doInit = Input(Bool())
    
    // Output accumulator
    val acc = Output(new RLWECiphertext(N, width))
    
    // Sample extraction
    val rlweIn = Input(new RLWECiphertext(N, width))
    val extractIdx = Input(UInt(log2Ceil(N).W))
    val doExtract = Input(Bool())
    val lweOut = Output(new LWECiphertext(N, width))
  })
  
  // Accumulator storage
  val accReg = Reg(new RLWECiphertext(N, width))
  
  // Initialize: acc = X^{b'} * v(X)
  // where b' = round(b * 2N / q)
  when(io.doInit) {
    // Compute initial rotation
    val bScaled = (io.initPhase * (2 * N).U) / modulus.U
    
    // Rotate test polynomial by bScaled
    val xkMult = Module(new PolynomialXkMult(N, width, modulus))
    xkMult.io.a := io.testPoly
    xkMult.io.k := bScaled
    
    // Trivial encryption of rotated test polynomial
    for (i <- 0 until N) {
      accReg.a(i) := 0.U
    }
    accReg.b := xkMult.io.out
  }
  
  io.acc := accReg
  
  // Sample extraction
  val extractor = Module(new LWESampleExtract(N, width, modulus))
  extractor.io.rlweA := io.rlweIn.a
  extractor.io.rlweB := io.rlweIn.b
  extractor.io.extractIdx := io.extractIdx
  
  io.lweOut := Mux(io.doExtract, extractor.io.lweOut, 
                   0.U.asTypeOf(new LWECiphertext(N, width)))
}

/**
  * Full Bootstrapping Unit
  * 
  * Complete TFHE bootstrapping pipeline:
  * 1. Initialize accumulator with test polynomial
  * 2. Perform blind rotation
  * 3. Extract LWE sample
  * 4. Key switch to original key format
  * 
  * @param n LWE dimension (original)
  * @param N Ring dimension (for bootstrapping)
  * @param width Bit width
  * @param modulus Prime modulus
  * @param ell RGSW decomposition length
  * @param baseKS Key switch base
  * @param logKS Key switch digits
  */
class FullBootstrapping(n: Int, N: Int, width: Int, modulus: Int, ell: Int, baseKS: Int, logKS: Int) extends Module {
  val io = IO(new Bundle {
    val lweIn = Input(new LWECiphertext(n, width))
    val bsk = Input(new BootstrappingKey(n, N, width, ell))
    val ksk = Input(Vec(N, Vec(logKS, new LWECiphertext(n, width))))
    val testPoly = Input(Vec(N, UInt(width.W)))
    val twiddles = Input(Vec(N, UInt(width.W)))
    val invTwiddles = Input(Vec(N, UInt(width.W)))
    val start = Input(Bool())
    val lweOut = Output(new LWECiphertext(n, width))
    val done = Output(Bool())
  })
  
  // State machine
  val sIdle :: sInit :: sBlindRotate :: sExtract :: sKeySwitch :: sDone = Enum(6)
  val state = RegInit(sIdle)
  
  // Sub-modules
  val accInit = Module(new BootstrapAccumulator(N, width, modulus))
  val blindRotate = Module(new BlindRotation(n, N, width, modulus, ell))
  val keySwitch = Module(new LWEKeySwitch(N, width, modulus, baseKS, logKS))
  
  // Intermediate results
  val accAfterBR = Reg(new RLWECiphertext(N, width))
  val extractedLWE = Reg(new LWECiphertext(N, width))
  
  io.done := (state === sDone)
  io.lweOut := keySwitch.io.ctOut
  
  // Default connections
  accInit.io.initPhase := io.lweIn.b
  accInit.io.testPoly := io.testPoly
  accInit.io.doInit := (state === sInit)
  accInit.io.rlweIn := accAfterBR
  accInit.io.extractIdx := 0.U
  accInit.io.doExtract := (state === sExtract)
  
  blindRotate.io.acc := accInit.io.acc
  blindRotate.io.lweA := io.lweIn.a
  blindRotate.io.bsk := io.bsk
  blindRotate.io.twiddles := io.twiddles
  blindRotate.io.invTwiddles := io.invTwiddles
  blindRotate.io.start := (state === sBlindRotate) && RegNext(state =/= sBlindRotate)
  
  keySwitch.io.ctIn := extractedLWE
  keySwitch.io.ksk := io.ksk
  keySwitch.io.start := (state === sKeySwitch) && RegNext(state =/= sKeySwitch)
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sInit
      }
    }
    
    is(sInit) {
      // Initialize accumulator with rotated test polynomial
      state := sBlindRotate
    }
    
    is(sBlindRotate) {
      when(blindRotate.io.done) {
        accAfterBR := blindRotate.io.result
        state := sExtract
      }
    }
    
    is(sExtract) {
      extractedLWE := accInit.io.lweOut
      state := sKeySwitch
    }
    
    is(sKeySwitch) {
      when(keySwitch.io.done) {
        state := sDone
      }
    }
    
    is(sDone) {
      state := sIdle
    }
  }
}

/**
  * Programmable Bootstrapping
  * 
  * Extends standard bootstrapping to evaluate any function encoded
  * in the test polynomial. This enables:
  * - Arbitrary unary functions on encrypted data
  * - Lookup table evaluation
  * - Non-linear operations
  * 
  * @param n LWE dimension
  * @param N Ring dimension  
  * @param width Bit width
  * @param modulus Prime modulus
  * @param ell RGSW decomposition length
  */
class ProgrammableBootstrapping(n: Int, N: Int, width: Int, modulus: Int, ell: Int) extends Module {
  val io = IO(new Bundle {
    val lweIn = Input(new LWECiphertext(n, width))
    val bsk = Input(new BootstrappingKey(n, N, width, ell))
    val lookupTable = Input(Vec(N, UInt(width.W)))  // Programmable function
    val twiddles = Input(Vec(N, UInt(width.W)))
    val invTwiddles = Input(Vec(N, UInt(width.W)))
    val start = Input(Bool())
    val lweOut = Output(new LWECiphertext(N, width))  // Output in RLWE dimension
    val done = Output(Bool())
  })
  
  // State machine
  val sIdle :: sInit :: sBlindRotate :: sExtract :: sDone = Enum(5)
  val state = RegInit(sIdle)
  
  // Accumulator
  val accReg = Reg(new RLWECiphertext(N, width))
  
  // Blind rotation
  val blindRotate = Module(new BlindRotation(n, N, width, modulus, ell))
  
  // Sample extractor
  val extractor = Module(new LWESampleExtract(N, width, modulus))
  
  io.done := (state === sDone)
  
  // Initialize connections
  blindRotate.io.acc := accReg
  blindRotate.io.lweA := io.lweIn.a
  blindRotate.io.bsk := io.bsk
  blindRotate.io.twiddles := io.twiddles
  blindRotate.io.invTwiddles := io.invTwiddles
  blindRotate.io.start := (state === sBlindRotate) && RegNext(state =/= sBlindRotate)
  
  extractor.io.rlweA := blindRotate.io.result.a
  extractor.io.rlweB := blindRotate.io.result.b
  extractor.io.extractIdx := 0.U
  
  io.lweOut := extractor.io.lweOut
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        // Initialize accumulator: X^{b'} * v(X)
        val bScaled = (io.lweIn.b * (2 * N).U) / modulus.U
        
        // Rotate lookup table and create trivial encryption
        for (i <- 0 until N) {
          accReg.a(i) := 0.U
        }
        
        // Simple rotation of lookup table (proper X^k mult)
        val xkMult = Module(new PolynomialXkMult(N, width, modulus))
        xkMult.io.a := io.lookupTable
        xkMult.io.k := bScaled
        accReg.b := xkMult.io.out
        
        state := sInit
      }
    }
    
    is(sInit) {
      state := sBlindRotate
    }
    
    is(sBlindRotate) {
      when(blindRotate.io.done) {
        state := sExtract
      }
    }
    
    is(sExtract) {
      state := sDone
    }
    
    is(sDone) {
      state := sIdle
    }
  }
}

object BootstrappingMain extends App {
  val n = 512
  val N = 1024
  val width = 13
  val q = 7681
  val ell = 3
  
  emitVerilog(new BlindRotation(n, N, width, q, ell), Array("--target-dir", "generated/fhe"))
  emitVerilog(new TestPolynomialGen(N, width, q), Array("--target-dir", "generated/fhe"))
}
