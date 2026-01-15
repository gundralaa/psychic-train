package cpu

import chisel3._
import chisel3.util._

class Memory extends Module {
  val io = IO(new Bundle {
    val imem_addr = Input(UInt(32.W))
    val imem_instr = Output(UInt(32.W))
    
    val dmem_addr = Input(UInt(32.W))
    val dmem_wdata = Input(UInt(32.W))
    val dmem_memWrite = Input(Bool())
    val dmem_memRead = Input(Bool())
    val dmem_rdata = Output(UInt(32.W))
  })

  // Instruction Memory (64KB - initialized with Loopback)
  val imem = Mem(16384, UInt(32.W)) // 16K x 32-bit = 64KB
  
  // Initialize with program
  // Note: loadMemoryFromFile is standard way, but here we can just write logic to init or use hardcoded Vec
  // Since Mem cannot be initialized inline easily in Chisel without file, I'll use a Vec for small program
  // Or just rely on synthesis tools loading it. 
  // For simulation/verilog, using `VecInit` is better for small ROMs.
  
  val program = VecInit(Seq(
    "h200001B7".U(32.W), // lui x3, 0x20000
    "h0041A083".U(32.W), // lw x1, 4(x3)
    "h0010F093".U(32.W), // andi x1, x1, 1
    "hFE008CE3".U(32.W), // beq x1, x0, -8
    "h0001A103".U(32.W), // lw x2, 0(x3)
    "h0041A083".U(32.W), // lw x1, 4(x3)
    "h0020F093".U(32.W), // andi x1, x1, 2
    "hFE008CE3".U(32.W), // beq x1, x0, -8
    "h0021A023".U(32.W), // sw x2, 0(x3)
    "hFE1FF06F".U(32.W)  // jal x0, -32
  ))
  
  // Map IMEM address (byte address) to index (word address)
  val imem_idx = io.imem_addr >> 2
  
  io.imem_instr := 0.U
  when (imem_idx < program.length.U) {
    io.imem_instr := program(imem_idx)
  } .otherwise {
    io.imem_instr := 0.U // NOP
  }

  // Data Memory (64KB)
  val dmem = Mem(16384, UInt(32.W))
  val dmem_idx = io.dmem_addr >> 2
  
  io.dmem_rdata := 0.U
  
  // Simple check if address is in RAM range (0x10000000)
  // For this simple example, we assume RAM is at 0x1000xxxx
  // But actually the UART is at 0x2000xxxx
  // If address matches RAM (let's say 0x1000xxxx), we access RAM.
  // Else output 0 (handled by Top for UART)
  
  val is_ram = (io.dmem_addr & "hFFFF0000".U) === "h10000000".U
  
  when (is_ram && io.dmem_memRead) {
    io.dmem_rdata := dmem(dmem_idx)
  }
  
  when (is_ram && io.dmem_memWrite) {
    dmem(dmem_idx) := io.dmem_wdata
  }
}
