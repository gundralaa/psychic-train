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

## CPU

A 5-stage pipelined RISC-V CPU implementation with hazard detection and forwarding.

### Structure
- `src/main/scala/cpu/Core.scala`: Main CPU core with pipeline stages
- `src/main/scala/cpu/Alu.scala`: Arithmetic Logic Unit
- `src/main/scala/cpu/Control.scala`: Control signal generation
- `src/main/scala/cpu/RegisterFile.scala`: Register file
- `src/main/scala/cpu/HazardUnit.scala`: Hazard detection and forwarding
- `src/main/scala/cpu/Memory.scala`: Memory interface
- `src/main/scala/cpu/PipelineRegisters.scala`: Pipeline stage registers

### How to Run

```bash
sbt "runMain cpu.CoreMain"
```

## Systolic Array

A toy systolic array implementation for matrix multiplication. Systolic arrays are specialized hardware architectures that efficiently perform matrix operations by passing data through a grid of processing elements (PEs) in a rhythmic, synchronized manner.

### What is a Systolic Array?

A systolic array is named after the "systolic" rhythm of the heart - data flows through the array like blood through the body, in regular pulses. Each processing element (PE) in the array:

1. Receives data from its neighbors
2. Performs a computation (multiply-accumulate)
3. Passes data to its next neighbors
4. Repeats every clock cycle

This architecture is highly efficient for matrix multiplication because:
- Data is reused as it flows through the array
- All PEs operate in parallel
- Memory bandwidth requirements are reduced
- Regular structure maps well to silicon

### Architecture

```
                    B[0]    B[1]    B[2]
                      │       │       │
                      ▼       ▼       ▼
              ┌───────┬───────┬───────┐
    A[0] ───► │ PE00  │ PE01  │ PE02  │ ───►
              ├───────┼───────┼───────┤
    A[1] ───► │ PE10  │ PE11  │ PE12  │ ───►
              ├───────┼───────┼───────┤
    A[2] ───► │ PE20  │ PE21  │ PE22  │ ───►
              └───────┴───────┴───────┘
                  │       │       │
                  ▼       ▼       ▼
```

For computing C = A × B:
- **Horizontal flow**: Elements of matrix A flow from west to east
- **Vertical flow**: Elements of matrix B flow from north to south
- **Result**: Each PE[i][j] accumulates C[i][j] = Σ(A[i][k] × B[k][j])

### Data Staggering

To ensure correct timing, inputs are staggered:
- Row 0 of A enters at cycle 0
- Row 1 of A enters at cycle 1 (delayed by 1 cycle)
- Row 2 of A enters at cycle 2 (delayed by 2 cycles)
- Similarly for columns of B

This ensures that the correct pairs of elements meet at each PE at the right time.

### Structure

- `src/main/scala/systolic/ProcessingElement.scala`: Individual PE that performs multiply-accumulate
- `src/main/scala/systolic/SystolicArray.scala`: NxN grid of connected PEs
- `src/main/scala/systolic/SystolicArrayTop.scala`: Top-level wrapper with control logic and demos

### Example: 2×2 Matrix Multiplication

```
| 1 2 |   | 5 6 |   | 1×5+2×7  1×6+2×8 |   | 19 22 |
| 3 4 | × | 7 8 | = | 3×5+4×7  3×6+4×8 | = | 43 50 |
```

The `SystolicArray2x2Demo` module demonstrates this computation.

### Dataflow Variants

The implementation includes two dataflow styles:

1. **Output Stationary** (`SystolicArray`): Results accumulate in each PE. Input data flows through.
2. **Weight Stationary** (`WeightStationarySystolicArray`): Weights are preloaded and stay in place. Activations flow through. Useful for neural network inference where weights are reused.

### How to Run

Generate Verilog for different configurations:

```bash
# Basic Processing Element
sbt "runMain systolic.ProcessingElementMain"

# 3x3 Systolic Array
sbt "runMain systolic.SystolicArrayMain"

# Weight Stationary variant
sbt "runMain systolic.WeightStationarySystolicArrayMain"

# Full top-level with control logic
sbt "runMain systolic.SystolicArrayTopMain"

# 2x2 Demo
sbt "runMain systolic.SystolicArray2x2DemoMain"

# 3x3 Demo
sbt "runMain systolic.SystolicArray3x3DemoMain"
```

### Configuration Parameters

All systolic array modules are parameterized:

| Parameter   | Default | Description                          |
|-------------|---------|--------------------------------------|
| `arraySize` | 3       | Size of the NxN array               |
| `dataWidth` | 8       | Bit width of input matrix elements  |
| `accWidth`  | 32      | Bit width of accumulator/result     |

### Timing

For an NxN systolic array:
- Data flows through in `2N - 1` cycles
- Total computation time: `3N - 1` cycles (including drain)
- Results are valid when `done` signal is asserted

## Fully Homomorphic Encryption (FHE) Accelerator

A hardware accelerator for Fully Homomorphic Encryption based on the TFHE scheme. FHE allows computation on encrypted data without decryption, enabling privacy-preserving computation.

### Overview

FHE is a cryptographic technique that enables:
- **Encrypted Computation**: Perform arbitrary computations on ciphertext
- **Privacy Preservation**: Data remains encrypted throughout processing
- **Secure Outsourcing**: Process sensitive data on untrusted servers

### Structure

```
src/main/scala/fhe/
├── ModularArithmetic.scala   # Base modular operations
├── NTT.scala                 # Number Theoretic Transform
├── PolynomialUnit.scala      # Polynomial arithmetic
├── LWE.scala                 # LWE encryption primitives
├── RLWE.scala                # Ring-LWE operations
├── HomomorphicGates.scala    # Homomorphic logic gates
├── Bootstrapping.scala       # Bootstrapping for noise reduction
├── FHEAccelerator.scala      # Top-level accelerator
└── README.md                 # Detailed documentation
```

### How to Run

```bash
# Full accelerator
sbt "runMain fhe.FHEAcceleratorMain"

# AXI-Lite wrapper
sbt "runMain fhe.FHEAcceleratorAXIMain"
```

### Documentation

For detailed documentation including architecture, mathematical background, configuration parameters, and usage examples, see the [FHE README](src/main/scala/fhe/README.md).
