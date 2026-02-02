//! Example: Chained Matrix Operations
//!
//! Demonstrates compiling expressions with multiple operations
//! like A @ B + C @ D or A @ B @ C.
//!
//! Run with: cargo run --example chained_ops

use numpy_to_systolic::{
    Analyzer, CodeGenerator, Parser, SystolicConfig, TilingStrategy,
};

fn main() {
    println!("=== Chained Matrix Operations Example ===\n");

    let config = SystolicConfig::new(3, 8, 32);

    // Example 1: A @ B + C @ D
    println!("Example 1: E = A @ B + C @ D");
    println!("Where A, B are 3x3 and C, D are 3x3\n");

    let mut parser = Parser::new("E = A @ B + C @ D");
    let program = parser.parse_program().unwrap();

    let mut analyzer = Analyzer::new();
    analyzer.define_matrix("A", (3, 3));
    analyzer.define_matrix("B", (3, 3));
    analyzer.define_matrix("C", (3, 3));
    analyzer.define_matrix("D", (3, 3));

    let typed = analyzer.analyze(program).unwrap();

    let tiler = TilingStrategy::new(config.clone());
    let tiled = tiler.tile_program(&typed).unwrap();

    let mut codegen = CodeGenerator::new(config.clone());
    let hw_program = codegen.generate(tiled).unwrap();

    println!("Generated {} pass(es) for systolic array", hw_program.passes.len());
    println!("(Each matmul requires 1 pass for 3x3 matrices on 3x3 array)");
    println!("(Addition is performed outside the array)");
    println!();

    for pass in &hw_program.passes {
        println!("  Pass {}: {}", pass.id, pass.description);
    }
    println!();

    // Example 2: Transpose operation
    println!("\nExample 2: F = A.T @ B");
    println!("Where A is 4x3, B is 4x3\n");

    let mut parser = Parser::new("F = A.T @ B");
    let program = parser.parse_program().unwrap();

    let mut analyzer = Analyzer::new();
    analyzer.define_matrix("A", (4, 3)); // A.T will be 3x4
    analyzer.define_matrix("B", (4, 3));

    let typed = analyzer.analyze(program).unwrap();
    println!("A shape: (4, 3)");
    println!("A.T shape: (3, 4)");
    println!("B shape: (4, 3)");
    println!("A.T @ B shape: (3, 3)");
    println!();

    let tiler = TilingStrategy::new(config.clone());
    let tiled = tiler.tile_program(&typed).unwrap();

    let mut codegen = CodeGenerator::new(config.clone());
    let hw_program = codegen.generate(tiled).unwrap();

    println!("Generated {} pass(es)", hw_program.passes.len());
    println!("Output shape: {:?}", hw_program.output_shape);
    println!();

    // Example 3: Multiple chained multiplications
    println!("\nExample 3: G = A @ B @ C (left-associative)");
    println!("Parsed as: (A @ B) @ C");
    println!("Where A is 2x3, B is 3x4, C is 4x2\n");

    // Note: We need to handle this carefully
    // A @ B = 2x4
    // (A @ B) @ C = 2x2
    let mut parser = Parser::new("G = A @ B @ C");
    let program = parser.parse_program().unwrap();

    let mut analyzer = Analyzer::new();
    analyzer.define_matrix("A", (2, 3));
    analyzer.define_matrix("B", (3, 4));
    analyzer.define_matrix("C", (4, 2));

    let typed = analyzer.analyze(program).unwrap();
    println!("Shape inference:");
    println!("  A @ B: (2, 3) @ (3, 4) = (2, 4)");
    println!("  (A @ B) @ C: (2, 4) @ (4, 2) = (2, 2)");
    println!();

    let tiler = TilingStrategy::new(config.clone());
    let tiled = tiler.tile_program(&typed).unwrap();

    let mut codegen = CodeGenerator::new(config.clone());
    let hw_program = codegen.generate(tiled).unwrap();

    println!("Generated {} pass(es)", hw_program.passes.len());
    for pass in &hw_program.passes {
        println!("  Pass {}: {}", pass.id, pass.description);
    }
    println!();
    println!("Output shape: {:?}", hw_program.output_shape);
    println!("Total cycles: {}", hw_program.total_cycles);

    // Example 4: Using numpy functions
    println!("\n\nExample 4: NumPy function support");
    println!("H = np.transpose(A) @ B\n");

    let mut parser = Parser::new("H = np.transpose(A) @ B");
    let program = parser.parse_program().unwrap();

    let mut analyzer = Analyzer::new();
    analyzer.define_matrix("A", (3, 4));
    analyzer.define_matrix("B", (3, 2));

    let typed = analyzer.analyze(program).unwrap();
    println!("np.transpose(A) with A=(3,4) -> (4,3)");
    println!("Result: (4,3) @ (3,2) = (4,2)");

    let tiler = TilingStrategy::new(config.clone());
    let tiled = tiler.tile_program(&typed).unwrap();

    let mut codegen = CodeGenerator::new(config);
    let hw_program = codegen.generate(tiled).unwrap();

    println!("Output shape: {:?}", hw_program.output_shape);
    println!("Total passes: {}", hw_program.passes.len());
}
