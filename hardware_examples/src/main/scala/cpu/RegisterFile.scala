package cpu

import chisel3._
import chisel3.util._

class RegisterFile extends Module {
  val io = IO(new Bundle {
    val rs1_addr = Input(UInt(5.W))
    val rs2_addr = Input(UInt(5.W))
    val rd_addr = Input(UInt(5.W))
    val wr_data = Input(UInt(32.W))
    val wr_en = Input(Bool())
    
    val rs1_data = Output(UInt(32.W))
    val rs2_data = Output(UInt(32.W))
  })

  val regs = Reg(Vec(32, UInt(32.W)))
  
  // Init x0 to 0 (though we handle read logic to enforce it)
  // Chisel registers are 0 on reset usually.
  
  // Write logic
  when(io.wr_en && io.rd_addr =/= 0.U) {
    regs(io.rd_addr) := io.wr_data
  }

  // Read logic - async read
  io.rs1_data := Mux(io.rs1_addr === 0.U, 0.U, regs(io.rs1_addr))
  io.rs2_data := Mux(io.rs2_addr === 0.U, 0.U, regs(io.rs2_addr))
}

object RegisterFileMain extends App {
  emitVerilog(new RegisterFile, Array("--target-dir", "generated/RegisterFile"))
}
