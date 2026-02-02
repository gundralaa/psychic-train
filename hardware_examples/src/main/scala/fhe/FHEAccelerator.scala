package fhe

import chisel3._
import chisel3.util._

/**
  * FHE Accelerator Configuration
  * 
  * Configurable parameters for the FHE hardware accelerator.
  * Different parameter sets provide different security/performance tradeoffs.
  */
case class FHEConfig(
  // LWE parameters
  n: Int = 512,           // LWE dimension
  q: Int = 1024,          // LWE modulus (power of 2)
  logQ: Int = 10,         // log2(q)
  
  // RLWE parameters for bootstrapping
  N: Int = 1024,          // Ring dimension (power of 2)
  bigQ: Int = 7681,       // RLWE prime modulus
  logBigQ: Int = 13,      // log2(bigQ) rounded up
  
  // Decomposition parameters
  baseG: Int = 4,         // Gadget base
  ell: Int = 6,           // Number of gadget digits
  baseKS: Int = 4,        // Key switching base
  logKS: Int = 5          // Key switching digits
)

/**
  * FHE Accelerator Top-Level
  * 
  * Complete FHE accelerator supporting:
  * - LWE encryption/decryption
  * - Homomorphic gates (AND, OR, XOR, NAND, etc.)
  * - Bootstrapping for noise reduction
  * - Key management
  * 
  * This accelerator implements a TFHE-style FHE scheme optimized for
  * evaluating Boolean circuits on encrypted data.
  */
class FHEAccelerator(config: FHEConfig = FHEConfig()) extends Module {
  val io = IO(new Bundle {
    // Command interface
    val cmd = Input(UInt(8.W))
    val cmdValid = Input(Bool())
    val cmdReady = Output(Bool())
    val resultValid = Output(Bool())
    
    // Data interface
    val dataIn = Input(UInt(32.W))
    val dataInValid = Input(Bool())
    val dataOut = Output(UInt(32.W))
    val dataOutValid = Output(Bool())
    
    // Status
    val busy = Output(Bool())
    val error = Output(Bool())
  })
  
  import config._
  
  // Commands
  object Cmd {
    val NOP        = 0.U(8.W)
    val ENCRYPT    = 1.U(8.W)
    val DECRYPT    = 2.U(8.W)
    val GATE_NOT   = 3.U(8.W)
    val GATE_AND   = 4.U(8.W)
    val GATE_OR    = 5.U(8.W)
    val GATE_XOR   = 6.U(8.W)
    val GATE_NAND  = 7.U(8.W)
    val GATE_NOR   = 8.U(8.W)
    val GATE_XNOR  = 9.U(8.W)
    val BOOTSTRAP  = 10.U(8.W)
    val LOAD_KEY   = 11.U(8.W)
    val STORE_CT   = 12.U(8.W)
    val LOAD_CT    = 13.U(8.W)
  }
  
  // State machine
  val sIdle :: sLoadData :: sProcess :: sOutput :: sError = Enum(5)
  val state = RegInit(sIdle)
  
  // Registers for operation
  val currentCmd = RegInit(0.U(8.W))
  val operandCount = RegInit(0.U(4.W))
  
  // Ciphertext registers
  val ct1 = Reg(new LWECiphertext(n, logQ))
  val ct2 = Reg(new LWECiphertext(n, logQ))
  val ctResult = Reg(new LWECiphertext(n, logQ))
  
  // Secret key register
  val secretKey = Reg(Vec(n, UInt(logQ.W)))
  
  // Data loading counter
  val loadCounter = RegInit(0.U(log2Ceil(n + 2).W))
  val outputCounter = RegInit(0.U(log2Ceil(n + 2).W))
  
  // Homomorphic gate units
  val notGate = Module(new HomomorphicNOT(n, logQ, q))
  val andGate = Module(new HomomorphicANDLinear(n, logQ, q))
  val orGate = Module(new HomomorphicORLinear(n, logQ, q))
  val xorGate = Module(new HomomorphicXORLinear(n, logQ, q))
  val nandGate = Module(new HomomorphicNANDLinear(n, logQ, q))
  val norGate = Module(new HomomorphicNORLinear(n, logQ, q))
  val xnorGate = Module(new HomomorphicXNORLinear(n, logQ, q))
  
  // Encryption/decryption units
  val encryptUnit = Module(new LWEEncrypt(n, logQ, q))
  val decryptUnit = Module(new LWEDecrypt(n, logQ, q))
  
  // Connect gate inputs
  notGate.io.in := ct1
  andGate.io.c1 := ct1
  andGate.io.c2 := ct2
  orGate.io.c1 := ct1
  orGate.io.c2 := ct2
  xorGate.io.c1 := ct1
  xorGate.io.c2 := ct2
  nandGate.io.c1 := ct1
  nandGate.io.c2 := ct2
  norGate.io.c1 := ct1
  norGate.io.c2 := ct2
  xnorGate.io.c1 := ct1
  xnorGate.io.c2 := ct2
  
  // Default encryption unit connections
  encryptUnit.io.a := ct1.a  // Use provided random values
  encryptUnit.io.secretKey := secretKey
  encryptUnit.io.noise := io.dataIn(logQ - 1, 0)  // Noise from input
  encryptUnit.io.message := io.dataIn(logQ).asBool
  encryptUnit.io.start := false.B
  
  decryptUnit.io.ciphertext := ct1
  decryptUnit.io.secretKey := secretKey
  decryptUnit.io.start := false.B
  
  // Output assignments
  io.cmdReady := (state === sIdle)
  io.busy := (state =/= sIdle)
  io.error := (state === sError)
  io.resultValid := (state === sOutput) && (outputCounter === (n + 1).U)
  io.dataOutValid := (state === sOutput)
  io.dataOut := 0.U
  
  // Output mux based on counter
  when(state === sOutput) {
    when(outputCounter < n.U) {
      io.dataOut := ctResult.a(outputCounter)
    }.elsewhen(outputCounter === n.U) {
      io.dataOut := ctResult.b
    }
  }
  
  // State machine
  switch(state) {
    is(sIdle) {
      when(io.cmdValid) {
        currentCmd := io.cmd
        loadCounter := 0.U
        outputCounter := 0.U
        operandCount := 0.U
        
        switch(io.cmd) {
          is(Cmd.GATE_NOT) {
            state := sLoadData
          }
          is(Cmd.GATE_AND, Cmd.GATE_OR, Cmd.GATE_XOR, 
             Cmd.GATE_NAND, Cmd.GATE_NOR, Cmd.GATE_XNOR) {
            state := sLoadData
          }
          is(Cmd.ENCRYPT, Cmd.DECRYPT) {
            state := sLoadData
          }
          is(Cmd.LOAD_KEY) {
            state := sLoadData
          }
          is(Cmd.NOP) {
            // Do nothing
          }
        }
      }
    }
    
    is(sLoadData) {
      when(io.dataInValid) {
        // Load ciphertext data
        when(currentCmd === Cmd.LOAD_KEY) {
          // Loading secret key
          when(loadCounter < n.U) {
            secretKey(loadCounter) := io.dataIn(logQ - 1, 0)
            loadCounter := loadCounter + 1.U
          }
          when(loadCounter === (n - 1).U) {
            state := sIdle  // Key loaded
          }
        }.elsewhen(currentCmd === Cmd.GATE_NOT || currentCmd === Cmd.ENCRYPT || currentCmd === Cmd.DECRYPT) {
          // Single operand gates
          when(loadCounter < n.U) {
            ct1.a(loadCounter) := io.dataIn(logQ - 1, 0)
          }.elsewhen(loadCounter === n.U) {
            ct1.b := io.dataIn(logQ - 1, 0)
          }
          loadCounter := loadCounter + 1.U
          
          when(loadCounter === n.U) {
            state := sProcess
          }
        }.otherwise {
          // Two operand gates
          when(operandCount === 0.U) {
            when(loadCounter < n.U) {
              ct1.a(loadCounter) := io.dataIn(logQ - 1, 0)
            }.elsewhen(loadCounter === n.U) {
              ct1.b := io.dataIn(logQ - 1, 0)
            }
            loadCounter := loadCounter + 1.U
            
            when(loadCounter === n.U) {
              loadCounter := 0.U
              operandCount := 1.U
            }
          }.otherwise {
            when(loadCounter < n.U) {
              ct2.a(loadCounter) := io.dataIn(logQ - 1, 0)
            }.elsewhen(loadCounter === n.U) {
              ct2.b := io.dataIn(logQ - 1, 0)
            }
            loadCounter := loadCounter + 1.U
            
            when(loadCounter === n.U) {
              state := sProcess
            }
          }
        }
      }
    }
    
    is(sProcess) {
      // Execute the operation
      switch(currentCmd) {
        is(Cmd.GATE_NOT) {
          ctResult := notGate.io.out
          state := sOutput
        }
        is(Cmd.GATE_AND) {
          ctResult := andGate.io.out
          state := sOutput
        }
        is(Cmd.GATE_OR) {
          ctResult := orGate.io.out
          state := sOutput
        }
        is(Cmd.GATE_XOR) {
          ctResult := xorGate.io.out
          state := sOutput
        }
        is(Cmd.GATE_NAND) {
          ctResult := nandGate.io.out
          state := sOutput
        }
        is(Cmd.GATE_NOR) {
          ctResult := norGate.io.out
          state := sOutput
        }
        is(Cmd.GATE_XNOR) {
          ctResult := xnorGate.io.out
          state := sOutput
        }
        is(Cmd.ENCRYPT) {
          encryptUnit.io.start := true.B
          when(encryptUnit.io.done) {
            ctResult := encryptUnit.io.ciphertext
            state := sOutput
          }
        }
        is(Cmd.DECRYPT) {
          decryptUnit.io.start := true.B
          when(decryptUnit.io.done) {
            // Output is just a single bit
            ctResult.b := decryptUnit.io.plaintext.asUInt
            for (i <- 0 until n) {
              ctResult.a(i) := 0.U
            }
            state := sOutput
          }
        }
      }
    }
    
    is(sOutput) {
      outputCounter := outputCounter + 1.U
      when(outputCounter > n.U) {
        state := sIdle
      }
    }
    
    is(sError) {
      // Stay in error state until reset
      when(!io.cmdValid) {
        state := sIdle
      }
    }
  }
}

/**
  * FHE Accelerator with AXI-Lite Interface
  * 
  * Wrapper providing a standard AXI-Lite slave interface for integration
  * with processor systems.
  */
class FHEAcceleratorAXI(config: FHEConfig = FHEConfig()) extends Module {
  val io = IO(new Bundle {
    // AXI-Lite Write Address Channel
    val awaddr = Input(UInt(32.W))
    val awvalid = Input(Bool())
    val awready = Output(Bool())
    
    // AXI-Lite Write Data Channel
    val wdata = Input(UInt(32.W))
    val wstrb = Input(UInt(4.W))
    val wvalid = Input(Bool())
    val wready = Output(Bool())
    
    // AXI-Lite Write Response Channel
    val bresp = Output(UInt(2.W))
    val bvalid = Output(Bool())
    val bready = Input(Bool())
    
    // AXI-Lite Read Address Channel
    val araddr = Input(UInt(32.W))
    val arvalid = Input(Bool())
    val arready = Output(Bool())
    
    // AXI-Lite Read Data Channel
    val rdata = Output(UInt(32.W))
    val rresp = Output(UInt(2.W))
    val rvalid = Output(Bool())
    val rready = Input(Bool())
    
    // Interrupt
    val irq = Output(Bool())
  })
  
  // Register addresses
  object Regs {
    val CTRL    = 0x00.U  // Control register
    val STATUS  = 0x04.U  // Status register
    val CMD     = 0x08.U  // Command register
    val DATA_IN = 0x0C.U  // Data input register
    val DATA_OUT = 0x10.U // Data output register
  }
  
  // Internal accelerator
  val accel = Module(new FHEAccelerator(config))
  
  // AXI state machines
  val awHandshake = RegInit(false.B)
  val wHandshake = RegInit(false.B)
  val arHandshake = RegInit(false.B)
  
  val writeAddr = RegInit(0.U(32.W))
  val readAddr = RegInit(0.U(32.W))
  val writeData = RegInit(0.U(32.W))
  val readData = RegInit(0.U(32.W))
  
  // Control/status registers
  val ctrlReg = RegInit(0.U(32.W))
  val cmdReg = RegInit(0.U(8.W))
  val cmdValid = RegInit(false.B)
  val dataInValid = RegInit(false.B)
  
  // Connect to accelerator
  accel.io.cmd := cmdReg
  accel.io.cmdValid := cmdValid
  accel.io.dataIn := writeData
  accel.io.dataInValid := dataInValid
  
  // AXI Write handling
  io.awready := !awHandshake
  io.wready := awHandshake && !wHandshake
  io.bvalid := awHandshake && wHandshake
  io.bresp := 0.U  // OKAY
  
  when(io.awvalid && io.awready) {
    writeAddr := io.awaddr
    awHandshake := true.B
  }
  
  when(io.wvalid && io.wready) {
    writeData := io.wdata
    wHandshake := true.B
    
    // Handle write to registers
    switch(writeAddr(7, 0)) {
      is(Regs.CTRL) {
        ctrlReg := io.wdata
      }
      is(Regs.CMD) {
        cmdReg := io.wdata(7, 0)
        cmdValid := true.B
      }
      is(Regs.DATA_IN) {
        dataInValid := true.B
      }
    }
  }
  
  when(io.bvalid && io.bready) {
    awHandshake := false.B
    wHandshake := false.B
    cmdValid := false.B
    dataInValid := false.B
  }
  
  // AXI Read handling
  io.arready := !arHandshake
  io.rvalid := arHandshake
  io.rdata := readData
  io.rresp := 0.U  // OKAY
  
  when(io.arvalid && io.arready) {
    readAddr := io.araddr
    arHandshake := true.B
    
    // Read from registers
    switch(io.araddr(7, 0)) {
      is(Regs.CTRL) {
        readData := ctrlReg
      }
      is(Regs.STATUS) {
        readData := Cat(0.U(29.W), accel.io.error, accel.io.busy, accel.io.cmdReady)
      }
      is(Regs.DATA_OUT) {
        readData := accel.io.dataOut
      }
    }
  }
  
  when(io.rvalid && io.rready) {
    arHandshake := false.B
  }
  
  // Interrupt generation
  io.irq := accel.io.resultValid
}

/**
  * Simplified FHE Demo Module
  * 
  * A minimal demonstration module showing basic homomorphic operations.
  * Useful for testing and understanding the FHE concepts.
  */
class FHEDemo(n: Int = 8, width: Int = 8, modulus: Int = 256) extends Module {
  val io = IO(new Bundle {
    // Plaintext inputs
    val plaintextA = Input(Bool())
    val plaintextB = Input(Bool())
    
    // Secret key
    val secretKey = Input(Vec(n, UInt(width.W)))
    
    // Random values for encryption
    val randomA = Input(Vec(n, UInt(width.W)))
    val randomB = Input(Vec(n, UInt(width.W)))
    val noiseA = Input(UInt(width.W))
    val noiseB = Input(UInt(width.W))
    
    // Control
    val start = Input(Bool())
    val gateType = Input(UInt(3.W))  // 0:NOT, 1:AND, 2:OR, 3:XOR, 4:NAND
    
    // Outputs
    val encryptedResult = Output(new LWECiphertext(n, width))
    val decryptedResult = Output(Bool())
    val done = Output(Bool())
  })
  
  // State machine
  val sIdle :: sEncryptA :: sEncryptB :: sGate :: sDecrypt :: sDone = Enum(6)
  val state = RegInit(sIdle)
  
  // Encrypted operands
  val ctA = Reg(new LWECiphertext(n, width))
  val ctB = Reg(new LWECiphertext(n, width))
  val ctResult = Reg(new LWECiphertext(n, width))
  
  // Encryption units
  val encryptA = Module(new LWEEncrypt(n, width, modulus))
  val encryptB = Module(new LWEEncrypt(n, width, modulus))
  val decrypt = Module(new LWEDecrypt(n, width, modulus))
  
  // Gate units
  val notGate = Module(new HomomorphicNOT(n, width, modulus))
  val andGate = Module(new HomomorphicANDLinear(n, width, modulus))
  val orGate = Module(new HomomorphicORLinear(n, width, modulus))
  val xorGate = Module(new HomomorphicXORLinear(n, width, modulus))
  val nandGate = Module(new HomomorphicNANDLinear(n, width, modulus))
  
  // Connect encryption units
  encryptA.io.a := io.randomA
  encryptA.io.secretKey := io.secretKey
  encryptA.io.noise := io.noiseA
  encryptA.io.message := io.plaintextA
  encryptA.io.start := (state === sEncryptA)
  
  encryptB.io.a := io.randomB
  encryptB.io.secretKey := io.secretKey
  encryptB.io.noise := io.noiseB
  encryptB.io.message := io.plaintextB
  encryptB.io.start := (state === sEncryptB)
  
  // Connect gate units
  notGate.io.in := ctA
  andGate.io.c1 := ctA
  andGate.io.c2 := ctB
  orGate.io.c1 := ctA
  orGate.io.c2 := ctB
  xorGate.io.c1 := ctA
  xorGate.io.c2 := ctB
  nandGate.io.c1 := ctA
  nandGate.io.c2 := ctB
  
  // Connect decrypt
  decrypt.io.ciphertext := ctResult
  decrypt.io.secretKey := io.secretKey
  decrypt.io.start := (state === sDecrypt)
  
  // Outputs
  io.encryptedResult := ctResult
  io.decryptedResult := decrypt.io.plaintext
  io.done := (state === sDone)
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sEncryptA
      }
    }
    
    is(sEncryptA) {
      when(encryptA.io.done) {
        ctA := encryptA.io.ciphertext
        state := Mux(io.gateType === 0.U, sGate, sEncryptB)  // NOT only needs one operand
      }
    }
    
    is(sEncryptB) {
      when(encryptB.io.done) {
        ctB := encryptB.io.ciphertext
        state := sGate
      }
    }
    
    is(sGate) {
      ctResult := MuxLookup(io.gateType, notGate.io.out, Seq(
        0.U -> notGate.io.out,
        1.U -> andGate.io.out,
        2.U -> orGate.io.out,
        3.U -> xorGate.io.out,
        4.U -> nandGate.io.out
      ))
      state := sDecrypt
    }
    
    is(sDecrypt) {
      when(decrypt.io.done) {
        state := sDone
      }
    }
    
    is(sDone) {
      when(!io.start) {
        state := sIdle
      }
    }
  }
}

object FHEAcceleratorMain extends App {
  emitVerilog(new FHEAccelerator(), Array("--target-dir", "generated/fhe"))
  emitVerilog(new FHEDemo(), Array("--target-dir", "generated/fhe"))
}

object FHEAcceleratorAXIMain extends App {
  emitVerilog(new FHEAcceleratorAXI(), Array("--target-dir", "generated/fhe"))
}
