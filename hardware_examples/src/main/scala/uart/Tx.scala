package uart

import chisel3._
import chisel3.util._

class Tx(frequency: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val txd = Output(Bool())
    val channel = Flipped(Decoupled(UInt(8.W)))
  })

  val cyclesPerBit = (frequency + baudRate / 2) / baudRate

  val sIdle :: sStart :: sData :: sStop :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val bitCounter = Reg(UInt(3.W))
  val baudCounter = Reg(UInt(log2Ceil(cyclesPerBit).W))
  val shiftReg = Reg(UInt(8.W))

  io.txd := true.B // Default high (idle)
  io.channel.ready := false.B

  switch(state) {
    is(sIdle) {
      io.txd := true.B
      when(io.channel.valid) {
        io.channel.ready := true.B
        shiftReg := io.channel.bits
        baudCounter := 0.U
        state := sStart
      }
    }
    is(sStart) {
      io.txd := false.B // Start bit is low
      when(baudCounter === (cyclesPerBit - 1).U) {
        baudCounter := 0.U
        bitCounter := 0.U
        state := sData
      } .otherwise {
        baudCounter := baudCounter + 1.U
      }
    }
    is(sData) {
      io.txd := shiftReg(0) // LSB first
      when(baudCounter === (cyclesPerBit - 1).U) {
        baudCounter := 0.U
        shiftReg := shiftReg >> 1
        when(bitCounter === 7.U) {
          state := sStop
        } .otherwise {
          bitCounter := bitCounter + 1.U
        }
      } .otherwise {
        baudCounter := baudCounter + 1.U
      }
    }
    is(sStop) {
      io.txd := true.B // Stop bit is high
      when(baudCounter === (cyclesPerBit - 1).U) {
        state := sIdle
      } .otherwise {
        baudCounter := baudCounter + 1.U
      }
    }
  }
}
