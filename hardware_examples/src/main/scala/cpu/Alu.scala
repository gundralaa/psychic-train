package cpu

import chisel3._
import chisel3.util._

class Alu extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val op = Input(UInt(4.W))
    val out = Output(UInt(32.W))
    val zero = Output(Bool())
  })

  io.out := 0.U
  
  val shamt = io.b(4, 0)

  switch(io.op) {
    is(AluOps.ADD)  { io.out := io.a + io.b }
    is(AluOps.SUB)  { io.out := io.a - io.b }
    is(AluOps.AND)  { io.out := io.a & io.b }
    is(AluOps.OR)   { io.out := io.a | io.b }
    is(AluOps.XOR)  { io.out := io.a ^ io.b }
    is(AluOps.SLT)  { io.out := (io.a.asSInt < io.b.asSInt).asUInt }
    is(AluOps.SLTU) { io.out := (io.a < io.b).asUInt }
    is(AluOps.SLL)  { io.out := io.a << shamt }
    is(AluOps.SRL)  { io.out := io.a >> shamt }
    is(AluOps.SRA)  { io.out := (io.a.asSInt >> shamt).asUInt }
    is(AluOps.COPY_A) { io.out := io.a }
    is(AluOps.COPY_B) { io.out := io.b }
  }

  io.zero := (io.out === 0.U)
}

object AluMain extends App {
  emitVerilog(new Alu, Array("--target-dir", "generated/Alu"))
}
