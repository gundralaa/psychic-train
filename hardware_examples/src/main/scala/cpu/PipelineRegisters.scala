package cpu

import chisel3._

class IF_ID_Bundle extends Bundle {
  val pc = UInt(32.W)
  val instr = UInt(32.W)
}

class ID_EX_Bundle extends Bundle {
  val pc = UInt(32.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val rs1Data = UInt(32.W)
  val rs2Data = UInt(32.W)
  val imm = UInt(32.W)
  
  // Control signals
  val aluOp = UInt(4.W)
  val op1Sel = UInt(1.W)
  val op2Sel = UInt(1.W)
  val memWrite = Bool()
  val memRead = Bool()
  val regWrite = Bool()
  val wbSel = UInt(2.W)
  val branch = Bool()
  val jump = Bool()
}

class EX_MEM_Bundle extends Bundle {
  val pc = UInt(32.W) // for debug or exception
  val rd = UInt(5.W)
  val aluResult = UInt(32.W)
  val memWriteData = UInt(32.W)
  
  // Control signals
  val memWrite = Bool()
  val memRead = Bool()
  val regWrite = Bool()
  val wbSel = UInt(2.W)
}

class MEM_WB_Bundle extends Bundle {
  val pc = UInt(32.W)
  val rd = UInt(5.W)
  val aluResult = UInt(32.W)
  val memReadData = UInt(32.W)
  
  // Control signals
  val regWrite = Bool()
  val wbSel = UInt(2.W)
}
