package cpu

import chisel3._
import chisel3.util._
import Instructions._

object ControlSignals {
  val Y = true.B
  val N = false.B
  
  // ALU Source
  val OP1_RS1 = 0.U(1.W)
  val OP1_PC  = 1.U(1.W)
  
  val OP2_RS2 = 0.U(1.W)
  val OP2_IMM = 1.U(1.W)
  
  // Writeback source
  val WB_ALU = 0.U(2.W)
  val WB_MEM = 1.U(2.W)
  val WB_PC  = 2.U(2.W)
  
  // Imm Type
  val IMM_I = 0.U(3.W)
  val IMM_S = 1.U(3.W)
  val IMM_B = 2.U(3.W)
  val IMM_U = 3.U(3.W)
  val IMM_J = 4.U(3.W)
  val IMM_Z = 5.U(3.W) // For R-type
}

import ControlSignals._

class Control extends Module {
  val io = IO(new Bundle {
    val instr = Input(UInt(32.W))
    val op1Sel = Output(UInt(1.W))
    val op2Sel = Output(UInt(1.W))
    val memWrite = Output(Bool())
    val memRead = Output(Bool())
    val regWrite = Output(Bool())
    val wbSel = Output(UInt(2.W))
    val aluOp = Output(UInt(4.W))
    val immSel = Output(UInt(3.W))
    val branch = Output(Bool())
    val jump = Output(Bool())
  })

  // Default signals
  io.op1Sel := OP1_RS1
  io.op2Sel := OP2_RS2
  io.memWrite := N
  io.memRead := N
  io.regWrite := N
  io.wbSel := WB_ALU
  io.aluOp := AluOps.ADD
  io.immSel := IMM_I
  io.branch := N
  io.jump := N

  val opcode = io.instr
  
  // Decode logic
  val csignals = ListLookup(opcode,
    List(OP1_RS1, OP2_RS2, N, N, N, WB_ALU, AluOps.ADD, IMM_Z, N, N),
    Array(
      LUI   -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.COPY_B, IMM_U, N, N),
      AUIPC -> List(OP1_PC,  OP2_IMM, N, N, Y, WB_ALU, AluOps.ADD,    IMM_U, N, N),
      JAL   -> List(OP1_PC,  OP2_IMM, N, N, Y, WB_PC,  AluOps.ADD,    IMM_J, N, Y),
      JALR  -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_PC,  AluOps.ADD,    IMM_I, N, Y),
      
      BEQ   -> List(OP1_RS1, OP2_RS2, N, N, N, WB_ALU, AluOps.SUB,    IMM_B, Y, N),
      BNE   -> List(OP1_RS1, OP2_RS2, N, N, N, WB_ALU, AluOps.SUB,    IMM_B, Y, N),
      BLT   -> List(OP1_RS1, OP2_RS2, N, N, N, WB_ALU, AluOps.SLT,    IMM_B, Y, N),
      BGE   -> List(OP1_RS1, OP2_RS2, N, N, N, WB_ALU, AluOps.SLT,    IMM_B, Y, N),
      BLTU  -> List(OP1_RS1, OP2_RS2, N, N, N, WB_ALU, AluOps.SLTU,   IMM_B, Y, N),
      BGEU  -> List(OP1_RS1, OP2_RS2, N, N, N, WB_ALU, AluOps.SLTU,   IMM_B, Y, N),
      
      LB    -> List(OP1_RS1, OP2_IMM, N, Y, Y, WB_MEM, AluOps.ADD,    IMM_I, N, N),
      LH    -> List(OP1_RS1, OP2_IMM, N, Y, Y, WB_MEM, AluOps.ADD,    IMM_I, N, N),
      LW    -> List(OP1_RS1, OP2_IMM, N, Y, Y, WB_MEM, AluOps.ADD,    IMM_I, N, N),
      LBU   -> List(OP1_RS1, OP2_IMM, N, Y, Y, WB_MEM, AluOps.ADD,    IMM_I, N, N),
      LHU   -> List(OP1_RS1, OP2_IMM, N, Y, Y, WB_MEM, AluOps.ADD,    IMM_I, N, N),
      
      SB    -> List(OP1_RS1, OP2_IMM, Y, N, N, WB_ALU, AluOps.ADD,    IMM_S, N, N),
      SH    -> List(OP1_RS1, OP2_IMM, Y, N, N, WB_ALU, AluOps.ADD,    IMM_S, N, N),
      SW    -> List(OP1_RS1, OP2_IMM, Y, N, N, WB_ALU, AluOps.ADD,    IMM_S, N, N),
      
      ADDI  -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.ADD,    IMM_I, N, N),
      SLTI  -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.SLT,    IMM_I, N, N),
      SLTIU -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.SLTU,   IMM_I, N, N),
      XORI  -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.XOR,    IMM_I, N, N),
      ORI   -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.OR,     IMM_I, N, N),
      ANDI  -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.AND,    IMM_I, N, N),
      SLLI  -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.SLL,    IMM_I, N, N),
      SRLI  -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.SRL,    IMM_I, N, N),
      SRAI  -> List(OP1_RS1, OP2_IMM, N, N, Y, WB_ALU, AluOps.SRA,    IMM_I, N, N),
      
      ADD   -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.ADD,    IMM_Z, N, N),
      SUB   -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.SUB,    IMM_Z, N, N),
      SLL   -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.SLL,    IMM_Z, N, N),
      SLT   -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.SLT,    IMM_Z, N, N),
      SLTU  -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.SLTU,   IMM_Z, N, N),
      XOR   -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.XOR,    IMM_Z, N, N),
      SRL   -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.SRL,    IMM_Z, N, N),
      SRA   -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.SRA,    IMM_Z, N, N),
      OR    -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.OR,     IMM_Z, N, N),
      AND   -> List(OP1_RS1, OP2_RS2, N, N, Y, WB_ALU, AluOps.AND,    IMM_Z, N, N)
    )
  )

  io.op1Sel   := csignals(0)
  io.op2Sel   := csignals(1)
  io.memWrite := csignals(2)
  io.memRead  := csignals(3)
  io.regWrite := csignals(4)
  io.wbSel    := csignals(5)
  io.aluOp    := csignals(6)
  io.immSel   := csignals(7)
  io.branch   := csignals(8)
  io.jump     := csignals(9)
}

object ControlMain extends App {
  emitVerilog(new Control, Array("--target-dir", "generated/Control"))
}
