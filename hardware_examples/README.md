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

### What is Fully Homomorphic Encryption?

FHE is a cryptographic technique that enables:
- **Encrypted Computation**: Perform arbitrary computations on ciphertext
- **Privacy Preservation**: Data remains encrypted throughout processing
- **Secure Outsourcing**: Process sensitive data on untrusted servers

The key insight is that homomorphic operations on ciphertexts correspond to operations on the underlying plaintexts:
```
Enc(a) ⊕ Enc(b) = Enc(a XOR b)
Enc(a) ⊗ Enc(b) = Enc(a AND b)
```

### TFHE Scheme Overview

This implementation is based on TFHE (Fast Fully Homomorphic Encryption over the Torus), which provides:

1. **LWE Encryption**: Learning With Errors based encryption
   - Ciphertext: (a, b) where b = <a, s> + e + m·Δ
   - Security from the hardness of distinguishing noisy inner products

2. **Homomorphic Gates**: Boolean operations on encrypted bits
   - NOT, AND, OR, XOR, NAND, NOR, XNOR
   - Gate operations are linear combinations of ciphertexts

3. **Bootstrapping**: Noise reduction via homomorphic decryption
   - Refreshes ciphertexts to enable unlimited computation
   - Uses Ring-LWE for efficiency

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    FHE Accelerator                          │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────┐ │
│  │   LWE       │    │ Homomorphic │    │  Bootstrapping  │ │
│  │ Encrypt/    │───▶│   Gates     │───▶│     Unit        │ │
│  │ Decrypt     │    │             │    │                 │ │
│  └─────────────┘    └─────────────┘    └─────────────────┘ │
│         │                  │                    │          │
│         ▼                  ▼                    ▼          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Polynomial Arithmetic Unit              │   │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────────┐   │   │
│  │  │ Modular   │  │    NTT    │  │  Polynomial   │   │   │
│  │  │ Arithmetic│  │  Engine   │  │  Operations   │   │   │
│  │  └───────────┘  └───────────┘  └───────────────┘   │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

| Module | File | Description |
|--------|------|-------------|
| `ModularArithmetic` | `ModularArithmetic.scala` | Modular add/sub/mult, torus arithmetic, gadget decomposition |
| `NTT` | `NTT.scala` | Number Theoretic Transform for fast polynomial multiplication |
| `PolynomialUnit` | `PolynomialUnit.scala` | Polynomial add/sub/mult, X^k multiplication, automorphisms |
| `LWE` | `LWE.scala` | LWE encryption/decryption, key switching, sample extraction |
| `RLWE` | `RLWE.scala` | Ring-LWE operations, RGSW ciphertexts, external products |
| `HomomorphicGates` | `HomomorphicGates.scala` | All Boolean gates: NOT, AND, OR, XOR, NAND, NOR, XNOR, MUX |
| `Bootstrapping` | `Bootstrapping.scala` | Blind rotation, programmable bootstrapping |
| `FHEAccelerator` | `FHEAccelerator.scala` | Top-level accelerator with command interface |

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
└── FHEAccelerator.scala      # Top-level accelerator
```

### Mathematical Background

#### LWE (Learning With Errors)
An LWE sample is `(a, b)` where:
- `a ∈ Z_q^n` is a random vector
- `s ∈ Z_q^n` is the secret key
- `e` is a small error term
- `b = <a, s> + e + m·(q/2)` for message bit `m`

#### Ring-LWE
Works over polynomial ring `R_q = Z_q[X]/(X^N + 1)`:
- More efficient than plain LWE
- Enables fast polynomial multiplication via NTT
- Used in bootstrapping

#### Bootstrapping
The key operation that makes FHE "fully" homomorphic:
1. Initialize accumulator with test polynomial
2. Blind rotation using bootstrapping key (RGSW ciphertexts)
3. Sample extraction to get LWE ciphertext
4. Key switching to original key format

### How to Run

Generate Verilog for the FHE components:

```bash
# Modular arithmetic components
sbt "runMain fhe.ModularArithmeticMain"

# NTT components
sbt "runMain fhe.NTTMain"

# Polynomial unit
sbt "runMain fhe.PolynomialUnitMain"

# LWE operations
sbt "runMain fhe.LWEMain"

# RLWE operations
sbt "runMain fhe.RLWEMain"

# Homomorphic gates
sbt "runMain fhe.HomomorphicGatesMain"

# Bootstrapping
sbt "runMain fhe.BootstrappingMain"

# Full accelerator
sbt "runMain fhe.FHEAcceleratorMain"

# AXI-Lite wrapper
sbt "runMain fhe.FHEAcceleratorAXIMain"
```

### Configuration Parameters

The FHE accelerator is configurable via `FHEConfig`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `n` | 512 | LWE dimension (security parameter) |
| `q` | 1024 | LWE ciphertext modulus |
| `N` | 1024 | Ring dimension for RLWE |
| `bigQ` | 7681 | RLWE prime modulus |
| `baseG` | 4 | Gadget decomposition base |
| `ell` | 6 | Number of gadget digits |
| `baseKS` | 4 | Key switching base |
| `logKS` | 5 | Key switching digits |

### Supported Operations

The accelerator supports the following commands:

| Command | Code | Description |
|---------|------|-------------|
| `ENCRYPT` | 0x01 | Encrypt a plaintext bit |
| `DECRYPT` | 0x02 | Decrypt a ciphertext |
| `GATE_NOT` | 0x03 | Homomorphic NOT |
| `GATE_AND` | 0x04 | Homomorphic AND |
| `GATE_OR` | 0x05 | Homomorphic OR |
| `GATE_XOR` | 0x06 | Homomorphic XOR |
| `GATE_NAND` | 0x07 | Homomorphic NAND |
| `GATE_NOR` | 0x08 | Homomorphic NOR |
| `GATE_XNOR` | 0x09 | Homomorphic XNOR |
| `BOOTSTRAP` | 0x0A | Perform bootstrapping |
| `LOAD_KEY` | 0x0B | Load secret key |

### Example: Encrypted Boolean Circuit

```scala
// Create encrypted inputs
val encA = encrypt(plaintextA, secretKey)
val encB = encrypt(plaintextB, secretKey)

// Compute (A AND B) XOR (NOT A) homomorphically
val andResult = homomorphicAND(encA, encB)
val notResult = homomorphicNOT(encA)
val finalResult = homomorphicXOR(andResult, notResult)

// Decrypt result (matches plaintext computation)
val output = decrypt(finalResult, secretKey)
```

### Security Considerations

- **Parameter Selection**: Default parameters provide ~128-bit security
- **Noise Growth**: Bootstrapping required after certain number of operations
- **Key Management**: Bootstrapping keys are large; consider memory constraints

### Performance Notes

- **NTT**: O(N log N) for polynomial multiplication vs O(N²) naive
- **Bootstrapping**: Most expensive operation; dominates runtime
- **Parallelism**: Multiple gates can be evaluated independently
- **Pipelining**: NTT and polynomial operations are pipelined

### References

1. Chillotti et al., "TFHE: Fast Fully Homomorphic Encryption over the Torus"
2. Gentry, "A Fully Homomorphic Encryption Scheme"
3. Regev, "On Lattices, Learning with Errors, Random Linear Codes, and Cryptography"
