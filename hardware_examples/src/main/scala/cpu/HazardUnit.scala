package cpu

import chisel3._

class HazardUnit extends Module {
  val io = IO(new Bundle {
    // Forwarding
    val rs1 = Input(UInt(5.W))
    val rs2 = Input(UInt(5.W))
    
    val ex_mem_rd = Input(UInt(5.W))
    val ex_mem_regWrite = Input(Bool())
    
    val mem_wb_rd = Input(UInt(5.W))
    val mem_wb_regWrite = Input(Bool())
    
    val forwardA = Output(UInt(2.W)) // 00=Reg, 10=EX/MEM, 01=MEM/WB
    val forwardB = Output(UInt(2.W))
    
    // Stalling (Load Use)
    val id_ex_memRead = Input(Bool())
    val id_ex_rd = Input(UInt(5.W))
    val if_id_rs1 = Input(UInt(5.W))
    val if_id_rs2 = Input(UInt(5.W))
    
    val stall = Output(Bool())
    
    // Branch/Jump Flush
    val pcSrc = Input(Bool()) // Branch taken or Jump
    val flush = Output(Bool())
  })

  // Forwarding Logic
  io.forwardA := 0.U
  io.forwardB := 0.U
  
  // EX Hazard
  when (io.ex_mem_regWrite && (io.ex_mem_rd =/= 0.U) && (io.ex_mem_rd === io.rs1)) {
    io.forwardA := 2.U // 10
  } .elsewhen (io.mem_wb_regWrite && (io.mem_wb_rd =/= 0.U) && (io.mem_wb_rd === io.rs1)) {
    io.forwardA := 1.U // 01
  }
  
  when (io.ex_mem_regWrite && (io.ex_mem_rd =/= 0.U) && (io.ex_mem_rd === io.rs2)) {
    io.forwardB := 2.U // 10
  } .elsewhen (io.mem_wb_regWrite && (io.mem_wb_rd =/= 0.U) && (io.mem_wb_rd === io.rs2)) {
    io.forwardB := 1.U // 01
  }
  
  // Stall Logic (Load-Use)
  io.stall := false.B
  when (io.id_ex_memRead && ((io.id_ex_rd === io.if_id_rs1) || (io.id_ex_rd === io.if_id_rs2))) {
    io.stall := true.B
  }
  
  // Flush Logic
  io.flush := io.pcSrc
}

object HazardUnitMain extends App {
  emitVerilog(new HazardUnit, Array("--target-dir", "generated/HazardUnit"))
}
