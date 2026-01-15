package uart

import chisel3._
import chisel3.util._

class Uart(frequency: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val txd = Output(Bool())
    val rxChannel = Decoupled(UInt(8.W))
    val txChannel = Flipped(Decoupled(UInt(8.W)))
  })

  val tx = Module(new Tx(frequency, baudRate))
  val rx = Module(new Rx(frequency, baudRate))

  tx.io.txd <> io.txd
  tx.io.channel <> io.txChannel

  rx.io.rxd <> io.rxd
  rx.io.channel <> io.rxChannel
}

// Generate the Verilog
object UartMain extends App {
  emitVerilog(new Uart(50000000, 115200), Array("--target-dir", "generated"))
}
