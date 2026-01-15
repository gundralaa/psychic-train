package cpu

import chisel3._
import chisel3.util._

class ImmGen extends Module {
  val io = IO(new Bundle {
    val instr = Input(UInt(32.W))
    val immSel = Input(UInt(3.W))
    val imm = Output(UInt(32.W))
  })

  val inst = io.instr
  
  val immI = inst(31, 20).asSInt
  val immS = Cat(inst(31, 25), inst(11, 7)).asSInt
  val immB = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)).asSInt
  val immU = Cat(inst(31, 12), 0.U(12.W)).asSInt
  val immJ = Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)).asSInt

  io.imm := MuxLookup(io.immSel, 0.S, Array(
    ControlSignals.IMM_I -> immI,
    ControlSignals.IMM_S -> immS,
    ControlSignals.IMM_B -> immB,
    ControlSignals.IMM_U -> immU,
    ControlSignals.IMM_J -> immJ,
    ControlSignals.IMM_Z -> 0.S
  )).asUInt
}

object ImmGenMain extends App {
  emitVerilog(new ImmGen, Array("--target-dir", "generated/ImmGen"))
}
