package systolic

import chisel3._
import chisel3.util._

/**
  * Systolic Array for Matrix Multiplication
  * 
  * Computes C = A * B where:
  *   - A is an MxK matrix (rows of A flow horizontally)
  *   - B is a KxN matrix (columns of B flow vertically)
  *   - C is the MxN result matrix
  * 
  * For this toy example, we use a square NxN array that computes
  * the multiplication of two NxN matrices.
  * 
  * Data Flow (Output Stationary):
  *   - Matrix A elements flow from west to east (horizontally)
  *   - Matrix B elements flow from north to south (vertically)
  *   - Each PE accumulates its corresponding C element
  *   - Data is staggered: A[i] enters at cycle i, B[j] enters at cycle j
  * 
  * Example for 3x3 matrices:
  *   Cycle 0: A[0,0] enters row 0, B[0,0] enters col 0
  *   Cycle 1: A[0,1] enters row 0, A[1,0] enters row 1, B[0,1] enters col 0, B[1,0] enters col 1
  *   etc.
  * 
  * @param arraySize Size of the NxN array (default 3 for toy example)
  * @param dataWidth Width of input data in bits
  * @param accWidth Width of accumulator in bits
  */
class SystolicArray(arraySize: Int = 3, dataWidth: Int = 8, accWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    // Input data (staggered inputs for systolic flow)
    val a_inputs = Input(Vec(arraySize, SInt(dataWidth.W)))  // One input per row
    val b_inputs = Input(Vec(arraySize, SInt(dataWidth.W)))  // One input per column
    
    // Control signals
    val clear = Input(Bool())   // Clear all accumulators
    val enable = Input(Bool())  // Enable computation
    
    // Output results - 2D array of accumulated values
    val results = Output(Vec(arraySize, Vec(arraySize, SInt(accWidth.W))))
  })

  // Create 2D array of processing elements
  val pes = Seq.fill(arraySize, arraySize)(Module(new ProcessingElement(dataWidth, accWidth)))

  // Connect control signals to all PEs
  for (i <- 0 until arraySize) {
    for (j <- 0 until arraySize) {
      pes(i)(j).io.clear := io.clear
      pes(i)(j).io.enable := io.enable
    }
  }

  // Connect horizontal data flow (A matrix, west to east)
  for (i <- 0 until arraySize) {
    // First column gets external input
    pes(i)(0).io.a_in := io.a_inputs(i)
    
    // Chain remaining columns
    for (j <- 1 until arraySize) {
      pes(i)(j).io.a_in := pes(i)(j-1).io.a_out
    }
  }

  // Connect vertical data flow (B matrix, north to south)
  for (j <- 0 until arraySize) {
    // First row gets external input
    pes(0)(j).io.b_in := io.b_inputs(j)
    
    // Chain remaining rows
    for (i <- 1 until arraySize) {
      pes(i)(j).io.b_in := pes(i-1)(j).io.b_out
    }
  }

  // Connect outputs
  for (i <- 0 until arraySize) {
    for (j <- 0 until arraySize) {
      io.results(i)(j) := pes(i)(j).io.result
    }
  }
}

/**
  * Weight Stationary Systolic Array
  * 
  * Alternative dataflow where weights (from matrix B) are preloaded
  * and stay stationary in each PE. Activations (from matrix A) flow
  * horizontally, and partial sums accumulate vertically.
  * 
  * This is efficient when the same weight matrix is reused for
  * multiple input batches (common in neural networks).
  * 
  * @param arraySize Size of the NxN array
  * @param dataWidth Width of input data in bits  
  * @param accWidth Width of accumulator in bits
  */
class WeightStationarySystolicArray(arraySize: Int = 3, dataWidth: Int = 8, accWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    // Weight loading interface
    val weight_row = Input(Vec(arraySize, SInt(dataWidth.W)))
    val load_weight_row = Input(UInt(log2Ceil(arraySize + 1).W))  // Which row to load (arraySize = no load)
    
    // Activation inputs (one per row)
    val activations = Input(Vec(arraySize, SInt(dataWidth.W)))
    
    // Control
    val enable = Input(Bool())
    
    // Results (bottom row outputs the final partial sums)
    val results = Output(Vec(arraySize, SInt(accWidth.W)))
  })

  // Create 2D array of weight-stationary PEs
  val pes = Seq.fill(arraySize, arraySize)(Module(new WeightStationaryPE(dataWidth, accWidth)))

  // Connect PEs
  for (i <- 0 until arraySize) {
    for (j <- 0 until arraySize) {
      val pe = pes(i)(j)
      
      // Weight loading - each row loads simultaneously
      pe.io.weight_in := io.weight_row(j)
      pe.io.load_weight := (io.load_weight_row === i.U)
      
      // Enable signal
      pe.io.enable := io.enable
      
      // Activation flow (horizontal, west to east)
      if (j == 0) {
        pe.io.act_in := io.activations(i)
      } else {
        pe.io.act_in := pes(i)(j-1).io.act_out
      }
      
      // Partial sum flow (vertical, north to south)
      if (i == 0) {
        pe.io.psum_in := 0.S
      } else {
        pe.io.psum_in := pes(i-1)(j).io.psum_out
      }
    }
  }

  // Output from bottom row
  for (j <- 0 until arraySize) {
    io.results(j) := pes(arraySize - 1)(j).io.psum_out
  }
}

object SystolicArrayMain extends App {
  emitVerilog(new SystolicArray(3, 8, 32), Array("--target-dir", "generated/systolic"))
}

object WeightStationarySystolicArrayMain extends App {
  emitVerilog(new WeightStationarySystolicArray(3, 8, 32), Array("--target-dir", "generated/systolic"))
}
