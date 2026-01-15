package uart

import chisel3._
import chisel3.util._

class Rx(frequency: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val channel = Decoupled(UInt(8.W))
  })

  val cyclesPerBit = (frequency + baudRate / 2) / baudRate
  val halfCycles = cyclesPerBit / 2

  val sIdle :: sStartCheck :: sData :: sStop :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val bitCounter = Reg(UInt(3.W))
  val baudCounter = Reg(UInt(log2Ceil(cyclesPerBit).W))
  val shiftReg = Reg(UInt(8.W))
  val validReg = RegInit(false.B)

  // Double flop RXD for metastability
  val rxdReg = RegNext(RegNext(io.rxd, true.B), true.B)

  io.channel.valid := validReg
  io.channel.bits := shiftReg

  // If consumer is ready, we clear the valid flag
  when (io.channel.ready) {
      validReg := false.B
  }

  switch(state) {
    is(sIdle) {
      when(!rxdReg) { // Falling edge candidate for start bit
        baudCounter := 0.U
        state := sStartCheck
      }
    }
    is(sStartCheck) {
      when(baudCounter === (halfCycles - 1).U) {
        baudCounter := 0.U
        when(!rxdReg) { // Start bit confirmed low
          bitCounter := 0.U
          state := sData
        } .otherwise { // False alarm
          state := sIdle
        }
      } .otherwise {
        baudCounter := baudCounter + 1.U
      }
    }
    is(sData) {
      when(baudCounter === (cyclesPerBit - 1).U) {
        baudCounter := 0.U
        // LSB first. We shift right. New bit goes to MSB (index 7).
        shiftReg := Cat(rxdReg, shiftReg(7, 1))
        
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
      when(baudCounter === (cyclesPerBit - 1).U) {
        baudCounter := 0.U
        state := sIdle
        when(rxdReg) { // Stop bit valid (high)
           validReg := true.B
        }
      } .otherwise {
        baudCounter := baudCounter + 1.U
      }
    }
  }
}
