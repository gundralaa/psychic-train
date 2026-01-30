package systolic

import chisel3._
import chisel3.util._

/**
  * Processing Element (PE) for a systolic array.
  * 
  * Each PE performs a multiply-accumulate operation:
  *   accumulator += a_in * b_in
  * 
  * Data flow:
  *   - a_in flows horizontally (west to east), delayed by one cycle
  *   - b_in flows vertically (north to south), delayed by one cycle
  *   - Results accumulate in an internal register
  * 
  * @param dataWidth Width of input operands in bits
  * @param accWidth Width of accumulator (should be larger to prevent overflow)
  */
class ProcessingElement(dataWidth: Int = 8, accWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    // Data inputs from neighbors
    val a_in = Input(SInt(dataWidth.W))   // From west neighbor
    val b_in = Input(SInt(dataWidth.W))   // From north neighbor
    
    // Data outputs to neighbors
    val a_out = Output(SInt(dataWidth.W)) // To east neighbor
    val b_out = Output(SInt(dataWidth.W)) // To south neighbor
    
    // Control signals
    val clear = Input(Bool())              // Clear accumulator
    val enable = Input(Bool())             // Enable computation
    
    // Result output
    val result = Output(SInt(accWidth.W))
  })

  // Internal registers
  val a_reg = RegInit(0.S(dataWidth.W))
  val b_reg = RegInit(0.S(dataWidth.W))
  val acc = RegInit(0.S(accWidth.W))

  // Pass data to neighbors (systolic flow)
  when(io.enable) {
    a_reg := io.a_in
    b_reg := io.b_in
  }
  
  io.a_out := a_reg
  io.b_out := b_reg

  // Multiply-accumulate operation
  val product = (io.a_in * io.b_in).asSInt

  when(io.clear) {
    acc := 0.S
  }.elsewhen(io.enable) {
    acc := acc + product
  }

  io.result := acc
}

/**
  * A variant PE that supports weight stationary dataflow.
  * The weight is loaded once and reused for multiple input activations.
  * 
  * @param dataWidth Width of input operands in bits
  * @param accWidth Width of accumulator
  */
class WeightStationaryPE(dataWidth: Int = 8, accWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    // Weight loading
    val weight_in = Input(SInt(dataWidth.W))
    val load_weight = Input(Bool())
    
    // Activation input (flows through array)
    val act_in = Input(SInt(dataWidth.W))
    val act_out = Output(SInt(dataWidth.W))
    
    // Partial sum (flows vertically)
    val psum_in = Input(SInt(accWidth.W))
    val psum_out = Output(SInt(accWidth.W))
    
    // Control
    val enable = Input(Bool())
  })

  // Stationary weight register
  val weight = RegInit(0.S(dataWidth.W))
  
  // Pipeline register for activation
  val act_reg = RegInit(0.S(dataWidth.W))

  // Load or hold weight
  when(io.load_weight) {
    weight := io.weight_in
  }

  // Pass activation horizontally
  when(io.enable) {
    act_reg := io.act_in
  }
  io.act_out := act_reg

  // Compute and accumulate partial sum
  val product = (io.act_in * weight).asSInt
  
  when(io.enable) {
    io.psum_out := io.psum_in + product
  }.otherwise {
    io.psum_out := io.psum_in
  }
}

object ProcessingElementMain extends App {
  emitVerilog(new ProcessingElement(8, 32), Array("--target-dir", "generated/systolic"))
}
