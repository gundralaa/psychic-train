# NumPy to Systolic Array Compiler

A Rust compiler that converts NumPy-style matrix expressions into sequences of passes through a systolic array. This tool is designed to work with the Chisel systolic array hardware in `hardware_examples/src/main/scala/systolic/`.

## Features

- **NumPy Expression Parsing**: Supports matrix multiplication (`@`), addition (`+`), subtraction (`-`), transpose (`.T`), and NumPy functions
- **Automatic Tiling**: Handles matrices larger than the systolic array by breaking them into tiles
- **Multiple Output Formats**: JSON, Chisel test vectors, and human-readable output
- **Configurable Hardware**: Supports different array sizes and data widths

## Installation

```bash
cd numpy-to-systolic
cargo build --release
```

## Usage

### Command Line Interface

```bash
# Compile with literal matrices
numpy2systolic "C = [[1,2],[3,4]] @ [[5,6],[7,8]]"

# Compile with variable shapes
numpy2systolic "C = A @ B" -s A=4x3 -s B=3x5

# Specify array configuration
numpy2systolic "C = A @ B" -s A=6x6 -s B=6x6 -n 4 -d 16 -a 48

# Output as JSON
numpy2systolic "C = A @ B" -s A=3x3 -s B=3x3 --json

# Output Chisel test vectors
numpy2systolic "[[1,2],[3,4]] @ [[5,6],[7,8]]" --chisel

# Verbose output
numpy2systolic "C = A @ B" -s A=6x6 -s B=6x6 -v
```

### CLI Options

| Option | Description | Default |
|--------|-------------|---------|
| `-s, --shape NAME=RxC` | Define matrix shape (e.g., A=3x4) | - |
| `-n, --array-size N` | Systolic array size (NxN) | 3 |
| `-d, --data-width N` | Data width in bits | 8 |
| `-a, --acc-width N` | Accumulator width in bits | 32 |
| `-j, --json` | Output as JSON | false |
| `--chisel` | Output Chisel test format | false |
| `-v, --verbose` | Verbose output | false |
| `-f, --file PATH` | Read expression from file | - |

### Library API

```rust
use numpy_to_systolic::{compile_with_shapes, SystolicConfig};

fn main() {
    // Configure target hardware
    let config = SystolicConfig::new(3, 8, 32); // 3x3 array, 8-bit, 32-bit accum
    
    // Define matrix shapes
    let shapes = vec![("A", (4, 3)), ("B", (3, 5))];
    
    // Compile expression
    let program = compile_with_shapes("C = A @ B", &shapes, &config).unwrap();
    
    // Use the result
    println!("Total passes: {}", program.passes.len());
    println!("Total cycles: {}", program.total_cycles);
    
    // Export to JSON
    let json = program.to_json().unwrap();
    
    // Export to Chisel test format
    let chisel = program.to_chisel_test_format();
}
```

## Supported Syntax

### Matrix Operations

```python
# Matrix multiplication
C = A @ B
D = np.matmul(A, B)
E = np.dot(A, B)

# Element-wise operations
F = A + B
G = A - B
H = A * B   # element-wise

# Transpose
I = A.T
J = np.transpose(A)

# Chained operations
K = A @ B + C @ D
L = A @ B @ C        # Left-associative: (A @ B) @ C

# Literal matrices
M = [[1, 2], [3, 4]] @ [[5, 6], [7, 8]]
```

### NumPy Functions

```python
# Create matrices (for shape inference)
A = np.zeros((3, 4))
B = np.ones((4, 5))
I = np.eye(3)
```

## How It Works

### 1. Parsing

The compiler parses NumPy-style expressions into an Abstract Syntax Tree (AST):

```
"C = A @ B + D"
       ↓
   Assignment
   ├── target: "C"
   └── value: Add
       ├── MatMul(A, B)
       └── Variable(D)
```

### 2. Type Analysis

Shape inference propagates matrix dimensions through the expression tree:

```
A: (4, 3)
B: (3, 5)
A @ B: (4, 5)
```

### 3. Tiling

For matrices larger than the systolic array, the compiler generates a tiling strategy:

```
6x6 @ 6x6 on a 3x3 array:
┌─────┬─────┐   ┌─────┬─────┐   ┌─────┬─────┐
│ A00 │ A01 │   │ B00 │ B01 │   │ C00 │ C01 │
├─────┼─────┤ @ ├─────┼─────┤ = ├─────┼─────┤
│ A10 │ A11 │   │ B10 │ B11 │   │ C10 │ C11 │
└─────┴─────┘   └─────┴─────┘   └─────┴─────┘

C[i,j] = Σ A[i,k] @ B[k,j]  (accumulated across K dimension)
```

### 4. Code Generation

Each tile multiplication becomes a systolic array pass:

```json
{
  "id": 0,
  "description": "C[0:3, 0:3] += A[0:3, 0:3] @ B[0:3, 0:3]",
  "matrix_a": [1, 2, 0, 3, 4, 0, 0, 0, 0],
  "matrix_b": [5, 7, 0, 6, 8, 0, 0, 0, 0],
  "operation": "Initialize"
}
```

## Hardware Integration

### Connecting to the Chisel Systolic Array

The compiler generates data in the format expected by `SystolicArrayTop`:

- **Matrix A**: Row-major order, padded to array size
- **Matrix B**: Column-major order, padded to array size
- **Operations**: `Initialize` (clear accumulators), `Accumulate`, or `Final`

### Example: Driving the Hardware

```scala
// In your Chisel testbench or driver

// Load matrices from compiler output
for (i <- 0 until arraySize * arraySize) {
  poke(dut.io.matrixA(i), matrixA_0(i))
  poke(dut.io.matrixB(i), matrixB_0(i))
}

// Start computation
poke(dut.io.start, true.B)
step(1)
poke(dut.io.start, false.B)

// Wait for completion
while (!peek(dut.io.done).litToBoolean) {
  step(1)
}

// Read results
val results = (0 until arraySize * arraySize).map(i => peek(dut.io.matrixC(i)))
```

### Pass Operations

| Operation | Hardware Action |
|-----------|-----------------|
| `Initialize` | Clear accumulators before first K tile |
| `Accumulate` | Add partial product to existing accumulator |
| `Final` | Last K tile, result is complete |

## Examples

### Example 1: Simple 2x2 Multiplication

```bash
$ numpy2systolic "[[1,2],[3,4]] @ [[5,6],[7,8]]"

Compilation Results
====================
Target: 3x3 systolic array
Output shape: (2, 2)
Total passes: 1
Total cycles: 8

Pass 0:
  Matrix A (row-major): [1, 2, 0, 3, 4, 0, 0, 0, 0]
  Matrix B (col-major): [5, 7, 0, 6, 8, 0, 0, 0, 0]
```

Expected result: `[[19, 22], [43, 50]]`

### Example 2: Large Matrix with Tiling

```bash
$ numpy2systolic "C = A @ B" -s A=6x6 -s B=6x6

Total passes: 8
Pass 0: C[0:3, 0:3] += A[0:3, 0:3] @ B[0:3, 0:3]  (Initialize)
Pass 1: C[0:3, 0:3] += A[0:3, 3:6] @ B[3:6, 0:3]  (Final)
Pass 2: C[0:3, 3:6] += A[0:3, 0:3] @ B[0:3, 3:6]  (Initialize)
...
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    NumPy Expression                          │
│                   "C = A @ B + D"                            │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Lexer (lexer.rs)                                            │
│  Tokenizes input into: Ident, Number, @, +, etc.            │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Parser (parser.rs)                                          │
│  Builds AST with operator precedence                         │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Analyzer (analyzer.rs)                                      │
│  Type checking and shape inference                           │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Tiling Strategy (tiling.rs)                                 │
│  Generates tile decomposition for large matrices             │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Code Generator (codegen.rs)                                 │
│  Produces systolic array passes with data layout             │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Hardware Program (hardware.rs)                              │
│  JSON, Chisel test vectors, display format                   │
└─────────────────────────────────────────────────────────────┘
```

## Running Examples

```bash
# Basic 2x2 multiplication
cargo run --example matmul_2x2

# Large matrix tiling demonstration
cargo run --example matmul_large

# Chained operations (A @ B + C @ D)
cargo run --example chained_ops
```

## Testing

```bash
cargo test
```

## License

MIT License
