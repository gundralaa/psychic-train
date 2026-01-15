package cpu

import chisel3._
import chisel3.util._

class Core extends Module {
  val io = IO(new Bundle {
    // Instruction Memory Interface
    val imem_addr = Output(UInt(32.W))
    val imem_instr = Input(UInt(32.W))
    
    // Data Memory Interface
    val dmem_addr = Output(UInt(32.W))
    val dmem_wdata = Output(UInt(32.W))
    val dmem_memWrite = Output(Bool())
    val dmem_memRead = Output(Bool())
    val dmem_rdata = Input(UInt(32.W))
  })

  // ==============================================================================
  // PIPELINE REGISTERS
  // ==============================================================================
  val if_id_reg = RegInit(0.U.asTypeOf(new IF_ID_Bundle))
  val id_ex_reg = RegInit(0.U.asTypeOf(new ID_EX_Bundle))
  val ex_mem_reg = RegInit(0.U.asTypeOf(new EX_MEM_Bundle))
  val mem_wb_reg = RegInit(0.U.asTypeOf(new MEM_WB_Bundle))

  // ==============================================================================
  // WIRES (Forward Declarations)
  // ==============================================================================
  val wb_data = Wire(UInt(32.W)) // From WB stage
  val pcSrc = Wire(Bool())       // From EX stage
  val branchTarget = Wire(UInt(32.W)) // From EX stage

  // ==============================================================================
  // HAZARD UNIT
  // ==============================================================================
  val hazard = Module(new HazardUnit)
  hazard.io.rs1 := id_ex_reg.rs1
  hazard.io.rs2 := id_ex_reg.rs2
  hazard.io.ex_mem_rd := ex_mem_reg.rd
  hazard.io.ex_mem_regWrite := ex_mem_reg.regWrite
  hazard.io.mem_wb_rd := mem_wb_reg.rd
  hazard.io.mem_wb_regWrite := mem_wb_reg.regWrite
  
  hazard.io.id_ex_memRead := id_ex_reg.memRead
  hazard.io.id_ex_rd := id_ex_reg.rd
  hazard.io.if_id_rs1 := if_id_reg.instr(19, 15)
  hazard.io.if_id_rs2 := if_id_reg.instr(24, 20)
  
  hazard.io.pcSrc := pcSrc

  // ==============================================================================
  // FETCH STAGE
  // ==============================================================================
  val pc = RegInit(0.U(32.W))
  val next_pc = Wire(UInt(32.W))
  val pc_plus_4 = pc + 4.U
  
  next_pc := Mux(pcSrc, branchTarget, pc_plus_4)
  
  when (!hazard.stall) {
    pc := next_pc
  }

  io.imem_addr := pc
  
  when (hazard.flush) {
    if_id_reg := 0.U.asTypeOf(new IF_ID_Bundle)
  } .elsewhen (!hazard.stall) {
    if_id_reg.pc := pc
    if_id_reg.instr := io.imem_instr
  }
  
  // ==============================================================================
  // DECODE STAGE
  // ==============================================================================
  val c = Module(new Control)
  c.io.instr := if_id_reg.instr
  
  val regFile = Module(new RegisterFile)
  regFile.io.rs1_addr := if_id_reg.instr(19, 15)
  regFile.io.rs2_addr := if_id_reg.instr(24, 20)
  regFile.io.rd_addr  := mem_wb_reg.rd
  regFile.io.wr_data  := wb_data
  regFile.io.wr_en    := mem_wb_reg.regWrite
  
  val immGen = Module(new ImmGen)
  immGen.io.instr := if_id_reg.instr
  immGen.io.immSel := c.io.immSel

  when (hazard.flush || hazard.stall) {
    id_ex_reg := 0.U.asTypeOf(new ID_EX_Bundle)
  } .otherwise {
    id_ex_reg.pc := if_id_reg.pc
    id_ex_reg.rs1 := if_id_reg.instr(19, 15)
    id_ex_reg.rs2 := if_id_reg.instr(24, 20)
    id_ex_reg.rd := if_id_reg.instr(11, 7)
    id_ex_reg.rs1Data := regFile.io.rs1_data
    id_ex_reg.rs2Data := regFile.io.rs2_data
    id_ex_reg.imm := immGen.io.imm
    
    id_ex_reg.aluOp := c.io.aluOp
    id_ex_reg.op1Sel := c.io.op1Sel
    id_ex_reg.op2Sel := c.io.op2Sel
    id_ex_reg.memWrite := c.io.memWrite
    id_ex_reg.memRead := c.io.memRead
    id_ex_reg.regWrite := c.io.regWrite
    id_ex_reg.wbSel := c.io.wbSel
    id_ex_reg.branch := c.io.branch
    id_ex_reg.jump := c.io.jump
  }

  // ==============================================================================
  // EXECUTE STAGE
  // ==============================================================================
  val forwardA_data = MuxLookup(hazard.forwardA, id_ex_reg.rs1Data, Array(
    0.U -> id_ex_reg.rs1Data,
    1.U -> wb_data,             
    2.U -> ex_mem_reg.aluResult 
  ))
  
  val forwardB_data = MuxLookup(hazard.forwardB, id_ex_reg.rs2Data, Array(
    0.U -> id_ex_reg.rs2Data,
    1.U -> wb_data,             
    2.U -> ex_mem_reg.aluResult 
  ))
  
  val alu = Module(new Alu)
  alu.io.op := id_ex_reg.aluOp
  alu.io.a := Mux(id_ex_reg.op1Sel === ControlSignals.OP1_PC, id_ex_reg.pc, forwardA_data)
  alu.io.b := Mux(id_ex_reg.op2Sel === ControlSignals.OP2_IMM, id_ex_reg.imm, forwardB_data)
  
  val branchTaken = (id_ex_reg.branch && alu.io.zero) || id_ex_reg.jump
  pcSrc := branchTaken
  
  val branchTargetCalc = id_ex_reg.pc + id_ex_reg.imm
  branchTarget := Mux(id_ex_reg.jump, alu.io.out, branchTargetCalc)
  
  when (hazard.flush) {
     ex_mem_reg := 0.U.asTypeOf(new EX_MEM_Bundle)
  } .otherwise {
    ex_mem_reg.pc := id_ex_reg.pc
    ex_mem_reg.rd := id_ex_reg.rd
    ex_mem_reg.aluResult := alu.io.out
    ex_mem_reg.memWriteData := forwardB_data
    ex_mem_reg.memWrite := id_ex_reg.memWrite
    ex_mem_reg.memRead := id_ex_reg.memRead
    ex_mem_reg.regWrite := id_ex_reg.regWrite
    ex_mem_reg.wbSel := id_ex_reg.wbSel
  }

  // ==============================================================================
  // MEMORY STAGE
  // ==============================================================================
  io.dmem_addr := ex_mem_reg.aluResult
  io.dmem_wdata := ex_mem_reg.memWriteData
  io.dmem_memWrite := ex_mem_reg.memWrite
  io.dmem_memRead := ex_mem_reg.memRead
  
  mem_wb_reg.pc := ex_mem_reg.pc
  mem_wb_reg.rd := ex_mem_reg.rd
  mem_wb_reg.aluResult := ex_mem_reg.aluResult
  mem_wb_reg.memReadData := io.dmem_rdata
  mem_wb_reg.regWrite := ex_mem_reg.regWrite
  mem_wb_reg.wbSel := ex_mem_reg.wbSel

  // ==============================================================================
  // WRITEBACK STAGE
  // ==============================================================================
  wb_data := MuxLookup(mem_wb_reg.wbSel, mem_wb_reg.aluResult, Array(
    ControlSignals.WB_ALU -> mem_wb_reg.aluResult,
    ControlSignals.WB_MEM -> mem_wb_reg.memReadData,
    ControlSignals.WB_PC  -> (mem_wb_reg.pc + 4.U)
  ))
}

object CoreMain extends App {
  emitVerilog(new Core, Array("--target-dir", "generated/Core"))
}
