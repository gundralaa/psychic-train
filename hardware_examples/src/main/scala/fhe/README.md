# Fully Homomorphic Encryption (FHE) Hardware Accelerator

A hardware accelerator for Fully Homomorphic Encryption based on the TFHE scheme. FHE allows computation on encrypted data without decryption, enabling privacy-preserving computation.

## What is Fully Homomorphic Encryption?

FHE is a cryptographic technique that enables:
- **Encrypted Computation**: Perform arbitrary computations on ciphertext
- **Privacy Preservation**: Data remains encrypted throughout processing
- **Secure Outsourcing**: Process sensitive data on untrusted servers

The key insight is that homomorphic operations on ciphertexts correspond to operations on the underlying plaintexts:
```
Enc(a) ⊕ Enc(b) = Enc(a XOR b)
Enc(a) ⊗ Enc(b) = Enc(a AND b)
```

## TFHE Scheme Overview

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

## Architecture

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

## Key Components

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

## File Structure

```
fhe/
├── ModularArithmetic.scala   # Base modular operations
├── NTT.scala                 # Number Theoretic Transform
├── PolynomialUnit.scala      # Polynomial arithmetic
├── LWE.scala                 # LWE encryption primitives
├── RLWE.scala                # Ring-LWE operations
├── HomomorphicGates.scala    # Homomorphic logic gates
├── Bootstrapping.scala       # Bootstrapping for noise reduction
├── FHEAccelerator.scala      # Top-level accelerator
└── README.md                 # This file
```

## Mathematical Background

### LWE (Learning With Errors)

An LWE sample is `(a, b)` where:
- `a ∈ Z_q^n` is a random vector
- `s ∈ Z_q^n` is the secret key
- `e` is a small error term
- `b = <a, s> + e + m·(q/2)` for message bit `m`

The security of LWE comes from the computational hardness of distinguishing `(a, <a,s> + e)` from uniformly random `(a, b)`.

### Ring-LWE

Works over polynomial ring `R_q = Z_q[X]/(X^N + 1)`:
- More efficient than plain LWE (O(N) vs O(N²) for key operations)
- Enables fast polynomial multiplication via NTT
- Used in bootstrapping for efficiency

### Bootstrapping

The key operation that makes FHE "fully" homomorphic:
1. Initialize accumulator with test polynomial
2. Blind rotation using bootstrapping key (RGSW ciphertexts)
3. Sample extraction to get LWE ciphertext
4. Key switching to original key format

Bootstrapping "refreshes" a ciphertext by homomorphically evaluating the decryption circuit, reducing noise accumulated from prior operations.

## How to Run

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

## Configuration Parameters

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

## Supported Operations

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

## Example: Encrypted Boolean Circuit

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

## Security Considerations

- **Parameter Selection**: Default parameters provide ~128-bit security
- **Noise Growth**: Bootstrapping required after certain number of operations
- **Key Management**: Bootstrapping keys are large; consider memory constraints
- **Side Channels**: Hardware implementation should consider timing and power analysis

## Performance Notes

- **NTT**: O(N log N) for polynomial multiplication vs O(N²) naive
- **Bootstrapping**: Most expensive operation; dominates runtime
- **Parallelism**: Multiple gates can be evaluated independently
- **Pipelining**: NTT and polynomial operations are pipelined

## Implementation Details

### Modular Arithmetic
- Supports power-of-2 moduli (simple truncation) and prime moduli
- Montgomery multiplication for efficient modular reduction
- Torus arithmetic for TFHE representation

### NTT Engine
- Butterfly units for Cooley-Tukey FFT-style computation
- Both iterative (area-efficient) and parallel (throughput-optimized) variants
- Precomputed twiddle factors stored in ROM

### Homomorphic Gates
- Linear operations preserve noise additively
- Gate bootstrapping cleans up noise after each gate
- MUX gate enables conditional operations

## References

1. Chillotti et al., "TFHE: Fast Fully Homomorphic Encryption over the Torus"
2. Gentry, "A Fully Homomorphic Encryption Scheme"
3. Regev, "On Lattices, Learning with Errors, Random Linear Codes, and Cryptography"
4. Fan & Vercauteren, "Somewhat Practical Fully Homomorphic Encryption"
