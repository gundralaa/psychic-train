package cpu

import chisel3._
import chisel3.util._
import uart.Uart

class Top extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val txd = Output(Bool())
  })

  val core = Module(new Core)
  val memory = Module(new Memory)
  val uart = Module(new Uart(50000000, 115200)) // Assuming 50MHz clock, 115200 baud
  
  // Connect IMEM
  memory.io.imem_addr := core.io.imem_addr
  core.io.imem_instr := memory.io.imem_instr
  
  // Connect DMEM (Interconnect logic)
  memory.io.dmem_addr := core.io.dmem_addr
  memory.io.dmem_wdata := core.io.dmem_wdata
  memory.io.dmem_memWrite := core.io.dmem_memWrite
  memory.io.dmem_memRead := core.io.dmem_memRead
  
  // UART MMIO
  // Address 0x20000000: Data
  // Address 0x20000004: Status
  val is_uart = (core.io.dmem_addr & "hFFFF0000".U) === "h20000000".U
  val uart_addr = core.io.dmem_addr(3, 0)
  
  // UART Internal Logic
  uart.io.rxd := io.rxd
  io.txd := uart.io.txd
  
  // Reading from UART
  uart.io.rxChannel.ready := false.B
  
  // Default read data from Memory
  val rdata = Wire(UInt(32.W))
  rdata := memory.io.dmem_rdata
  
  when (is_uart && core.io.dmem_memRead) {
    when (uart_addr === 0.U) { // Data
       rdata := uart.io.rxChannel.bits
       uart.io.rxChannel.ready := true.B // Consumed
    } .elsewhen (uart_addr === 4.U) { // Status
       // Bit 0: RX Valid
       // Bit 1: TX Ready
       rdata := Cat(0.U(30.W), uart.io.txChannel.ready, uart.io.rxChannel.valid)
    }
  }
  
  core.io.dmem_rdata := rdata
  
  // Writing to UART
  uart.io.txChannel.valid := false.B
  uart.io.txChannel.bits := core.io.dmem_wdata(7, 0)
  
  when (is_uart && core.io.dmem_memWrite) {
    when (uart_addr === 0.U) { // Data
       uart.io.txChannel.valid := true.B
    }
  }
}

// Generate Verilog
object TopMain extends App {
  emitVerilog(new Top, Array("--target-dir", "generated"))
}
