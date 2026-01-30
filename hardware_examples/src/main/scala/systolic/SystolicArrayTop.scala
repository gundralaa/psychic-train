package systolic

import chisel3._
import chisel3.util._

/**
  * Control states for the systolic array operation
  */
object SystolicState extends ChiselEnum {
  val sIdle, sLoad, sCompute, sDrain, sDone = Value
}

/**
  * Top-level wrapper for the Systolic Array with control logic.
  * 
  * This module provides a complete interface for performing matrix
  * multiplication using the systolic array. It handles:
  *   - Loading input matrices from memory
  *   - Staggering inputs for proper systolic flow
  *   - Timing the computation phases
  *   - Signaling when results are ready
  * 
  * For a 3x3 toy example, computes C = A * B where A, B, C are 3x3 matrices.
  * 
  * Timing for NxN matrix multiplication:
  *   - 2*N - 1 cycles for all data to flow through
  *   - Results are valid after the drain phase completes
  * 
  * @param arraySize Size of NxN array (default 3)
  * @param dataWidth Width of matrix elements in bits
  * @param accWidth Width of result accumulator
  */
class SystolicArrayTop(arraySize: Int = 3, dataWidth: Int = 8, accWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    // Control interface
    val start = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
    
    // Input matrices (flattened for simple interface)
    // Matrix A: row-major order [A00, A01, A02, A10, A11, A12, A20, A21, A22]
    val matrixA = Input(Vec(arraySize * arraySize, SInt(dataWidth.W)))
    // Matrix B: column-major order for efficient column access
    val matrixB = Input(Vec(arraySize * arraySize, SInt(dataWidth.W)))
    
    // Output matrix C (row-major order)
    val matrixC = Output(Vec(arraySize * arraySize, SInt(accWidth.W)))
    val resultValid = Output(Bool())
  })

  import SystolicState._
  
  // Instantiate the systolic array
  val array = Module(new SystolicArray(arraySize, dataWidth, accWidth))
  
  // State machine
  val state = RegInit(sIdle)
  val cycleCount = RegInit(0.U(log2Ceil(3 * arraySize).W))
  
  // Input staging registers with staggered delays
  // For row i of matrix A, we need to delay by i cycles
  // For column j of matrix B, we need to delay by j cycles
  val aDelayRegs = Seq.tabulate(arraySize) { i =>
    Seq.fill(i)(RegInit(0.S(dataWidth.W)))
  }
  val bDelayRegs = Seq.tabulate(arraySize) { j =>
    Seq.fill(j)(RegInit(0.S(dataWidth.W)))
  }
  
  // Input data registers (loaded when start is asserted)
  val aRegs = Reg(Vec(arraySize, Vec(arraySize, SInt(dataWidth.W))))
  val bRegs = Reg(Vec(arraySize, Vec(arraySize, SInt(dataWidth.W))))
  
  // Column index for feeding data
  val colIdx = RegInit(0.U(log2Ceil(arraySize + 1).W))
  
  // Default outputs
  io.busy := (state =/= sIdle)
  io.done := (state === sDone)
  io.resultValid := (state === sDone)
  
  // Connect array control signals
  array.io.clear := (state === sIdle) && io.start
  array.io.enable := (state === sCompute)
  
  // State machine logic
  switch(state) {
    is(sIdle) {
      cycleCount := 0.U
      colIdx := 0.U
      
      when(io.start) {
        state := sLoad
        
        // Load matrices into internal registers
        for (i <- 0 until arraySize) {
          for (j <- 0 until arraySize) {
            aRegs(i)(j) := io.matrixA(i * arraySize + j)
            bRegs(i)(j) := io.matrixB(j * arraySize + i)  // B is column-major
          }
        }
      }
    }
    
    is(sLoad) {
      // Transition immediately to compute
      state := sCompute
      colIdx := 0.U
    }
    
    is(sCompute) {
      cycleCount := cycleCount + 1.U
      
      when(colIdx < arraySize.U) {
        colIdx := colIdx + 1.U
      }
      
      // Need 2*N - 1 cycles for computation plus some drain time
      when(cycleCount >= (2 * arraySize - 1 + arraySize).U) {
        state := sDone
      }
    }
    
    is(sDone) {
      when(!io.start) {
        state := sIdle
      }
    }
  }
  
  // Generate staggered inputs for the systolic array
  // Row i of matrix A is delayed by i cycles before entering
  // Column j of matrix B is delayed by j cycles before entering
  
  for (i <- 0 until arraySize) {
    val aInput = Wire(SInt(dataWidth.W))
    
    // Select which element of row i to feed based on cycle count
    when(state === sCompute && colIdx < arraySize.U) {
      aInput := aRegs(i)(colIdx)
    }.otherwise {
      aInput := 0.S
    }
    
    // Apply staggered delay for row i
    if (i == 0) {
      array.io.a_inputs(i) := aInput
    } else {
      // Shift through delay registers
      aDelayRegs(i)(0) := aInput
      for (d <- 1 until i) {
        aDelayRegs(i)(d) := aDelayRegs(i)(d-1)
      }
      array.io.a_inputs(i) := aDelayRegs(i)(i-1)
    }
  }
  
  for (j <- 0 until arraySize) {
    val bInput = Wire(SInt(dataWidth.W))
    
    // Select which element of column j to feed based on cycle count
    when(state === sCompute && colIdx < arraySize.U) {
      bInput := bRegs(j)(colIdx)
    }.otherwise {
      bInput := 0.S
    }
    
    // Apply staggered delay for column j
    if (j == 0) {
      array.io.b_inputs(j) := bInput
    } else {
      // Shift through delay registers
      bDelayRegs(j)(0) := bInput
      for (d <- 1 until j) {
        bDelayRegs(j)(d) := bDelayRegs(j)(d-1)
      }
      array.io.b_inputs(j) := bDelayRegs(j)(j-1)
    }
  }
  
  // Connect outputs (row-major order)
  for (i <- 0 until arraySize) {
    for (j <- 0 until arraySize) {
      io.matrixC(i * arraySize + j) := array.io.results(i)(j)
    }
  }
}

/**
  * Simple test wrapper that demonstrates the systolic array
  * with hardcoded 2x2 matrices for minimal example.
  * 
  * Computes:
  *   | 1 2 |   | 5 6 |   | 1*5+2*7  1*6+2*8 |   | 19 22 |
  *   | 3 4 | * | 7 8 | = | 3*5+4*7  3*6+4*8 | = | 43 50 |
  */
class SystolicArray2x2Demo extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val results = Output(Vec(4, SInt(32.W)))
  })

  val top = Module(new SystolicArrayTop(2, 8, 32))
  
  // Hardcoded test matrices
  // Matrix A = [[1, 2], [3, 4]] (row-major)
  top.io.matrixA(0) := 1.S
  top.io.matrixA(1) := 2.S
  top.io.matrixA(2) := 3.S
  top.io.matrixA(3) := 4.S
  
  // Matrix B = [[5, 6], [7, 8]] (column-major: [5,7,6,8])
  top.io.matrixB(0) := 5.S
  top.io.matrixB(1) := 7.S
  top.io.matrixB(2) := 6.S
  top.io.matrixB(3) := 8.S
  
  top.io.start := io.start
  io.done := top.io.done
  
  for (i <- 0 until 4) {
    io.results(i) := top.io.matrixC(i)
  }
}

/**
  * 3x3 demonstration with example matrices.
  * 
  * Computes:
  *   | 1 2 3 |   | 1 0 0 |   | 1 2 3 |
  *   | 4 5 6 | * | 0 1 0 | = | 4 5 6 |  (identity matrix)
  *   | 7 8 9 |   | 0 0 1 |   | 7 8 9 |
  */
class SystolicArray3x3Demo extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val done = Output(Bool())
    val results = Output(Vec(9, SInt(32.W)))
  })

  val top = Module(new SystolicArrayTop(3, 8, 32))
  
  // Matrix A = [[1,2,3], [4,5,6], [7,8,9]] (row-major)
  top.io.matrixA(0) := 1.S
  top.io.matrixA(1) := 2.S
  top.io.matrixA(2) := 3.S
  top.io.matrixA(3) := 4.S
  top.io.matrixA(4) := 5.S
  top.io.matrixA(5) := 6.S
  top.io.matrixA(6) := 7.S
  top.io.matrixA(7) := 8.S
  top.io.matrixA(8) := 9.S
  
  // Matrix B = Identity 3x3 (column-major)
  // [[1,0,0], [0,1,0], [0,0,1]]
  // Column 0: [1,0,0], Column 1: [0,1,0], Column 2: [0,0,1]
  // Flattened column-major: [1,0,0, 0,1,0, 0,0,1]
  top.io.matrixB(0) := 1.S
  top.io.matrixB(1) := 0.S
  top.io.matrixB(2) := 0.S
  top.io.matrixB(3) := 0.S
  top.io.matrixB(4) := 1.S
  top.io.matrixB(5) := 0.S
  top.io.matrixB(6) := 0.S
  top.io.matrixB(7) := 0.S
  top.io.matrixB(8) := 1.S
  
  top.io.start := io.start
  io.done := top.io.done
  
  for (i <- 0 until 9) {
    io.results(i) := top.io.matrixC(i)
  }
}

object SystolicArrayTopMain extends App {
  emitVerilog(new SystolicArrayTop(3, 8, 32), Array("--target-dir", "generated/systolic"))
}

object SystolicArray2x2DemoMain extends App {
  emitVerilog(new SystolicArray2x2Demo, Array("--target-dir", "generated/systolic"))
}

object SystolicArray3x3DemoMain extends App {
  emitVerilog(new SystolicArray3x3Demo, Array("--target-dir", "generated/systolic"))
}
