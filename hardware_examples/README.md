# Hardware Examples (Chisel)

This directory contains hardware examples implemented in Chisel.

## UART

A simple UART Transmitter and Receiver implementation.

### Structure
- `src/main/scala/uart/Tx.scala`: UART Transmitter
- `src/main/scala/uart/Rx.scala`: UART Receiver
- `src/main/scala/uart/Uart.scala`: Top-level UART module and generator

### How to Run

To generate Verilog:

```bash
sbt "runMain uart.UartMain"
```
