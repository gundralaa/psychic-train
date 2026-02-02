//! Example: 2x2 Matrix Multiplication
//!
//! Demonstrates compiling a simple 2x2 matrix multiplication
//! that fits within the 3x3 systolic array.
//!
//! Run with: cargo run --example matmul_2x2

use numpy_to_systolic::{
    compile_with_shapes, Analyzer, CodeGenerator, Parser, SystolicConfig, TilingStrategy,
};

fn main() {
    println!("=== 2x2 Matrix Multiplication Example ===\n");

    // Configure for 3x3 systolic array (toy example from Chisel)
    let config = SystolicConfig::new(3, 8, 32);

    // Example 1: Using variable references with explicit shapes
    println!("Example 1: Variable references");
    println!("Expression: C = A @ B");
    println!("A shape: (2, 2), B shape: (2, 2)\n");

    let shapes = vec![("A", (2, 2)), ("B", (2, 2))];
    let result = compile_with_shapes("C = A @ B", &shapes, &config).unwrap();

    println!("Generated {} pass(es)", result.passes.len());
    println!("Output shape: {:?}", result.output_shape);
    println!("Total cycles: {}\n", result.total_cycles);

    // Example 2: Using literal matrices (matching Chisel demo)
    println!("Example 2: Literal matrices (matching SystolicArray2x2Demo)");
    println!("Expression: C = [[1, 2], [3, 4]] @ [[5, 6], [7, 8]]\n");

    let mut parser = Parser::new("C = [[1, 2], [3, 4]] @ [[5, 6], [7, 8]]");
    let program = parser.parse_program().unwrap();

    let mut analyzer = Analyzer::new();
    let typed = analyzer.analyze(program).unwrap();

    let tiler = TilingStrategy::new(config.clone());
    let tiled = tiler.tile_program(&typed).unwrap();

    let mut codegen = CodeGenerator::new(config.clone());
    let hw_program = codegen.generate(tiled).unwrap();

    println!("Pass 0 details:");
    let pass = &hw_program.passes[0];
    println!("  Matrix A (row-major, padded to 3x3):");
    println!("    {:?}", &pass.matrix_a);
    println!("  Matrix B (column-major, padded to 3x3):");
    println!("    {:?}", &pass.matrix_b);
    println!();

    // Expected result:
    // | 1 2 |   | 5 6 |   | 1*5+2*7  1*6+2*8 |   | 19 22 |
    // | 3 4 | * | 7 8 | = | 3*5+4*7  3*6+4*8 | = | 43 50 |
    println!("Expected result (computed manually):");
    println!("  | 19 22 |");
    println!("  | 43 50 |");
    println!();

    // Example 3: Export in different formats
    println!("Example 3: Export formats\n");

    println!("JSON output:");
    println!("{}\n", hw_program.to_json().unwrap());

    println!("Chisel test format:");
    println!("{}", hw_program.to_chisel_test_format());
}
