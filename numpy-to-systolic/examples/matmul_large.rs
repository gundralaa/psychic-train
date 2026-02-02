//! Example: Large Matrix Multiplication with Tiling
//!
//! Demonstrates how the compiler handles matrices larger than
//! the systolic array by tiling them.
//!
//! Run with: cargo run --example matmul_large

use numpy_to_systolic::{compile_with_shapes, SystolicConfig};

fn main() {
    println!("=== Large Matrix Multiplication with Tiling ===\n");

    // Configure for 3x3 systolic array
    let config = SystolicConfig::new(3, 8, 32);

    // Example 1: 6x6 matrices (needs 2x2 tiling in output)
    println!("Example 1: 6x6 @ 6x6 Matrix Multiplication");
    println!("Array size: 3x3");
    println!("Matrix sizes: A=(6,6), B=(6,6)\n");

    let shapes = vec![("A", (6, 6)), ("B", (6, 6))];
    let result = compile_with_shapes("C = A @ B", &shapes, &config).unwrap();

    println!("Tiling analysis:");
    println!("  - M tiles: {} (6 rows / 3 = 2)", 2);
    println!("  - N tiles: {} (6 cols / 3 = 2)", 2);
    println!("  - K tiles: {} (6 inner / 3 = 2)", 2);
    println!("  - Total passes: {} (2 * 2 * 2 = 8)", result.passes.len());
    println!();

    println!("Generated passes:");
    for pass in &result.passes {
        println!("  Pass {}: {}", pass.id, pass.description);
        println!(
            "    -> Output tile ({}, {}), Operation: {:?}",
            pass.output_tile.tile_row, pass.output_tile.tile_col, pass.operation
        );
    }
    println!();

    println!("Output shape: {:?}", result.output_shape);
    println!("Total cycles: {}", result.total_cycles);
    println!();

    // Example 2: Non-square matrices
    println!("\nExample 2: 4x6 @ 6x8 Matrix Multiplication");

    let shapes = vec![("A", (4, 6)), ("B", (6, 8))];
    let result = compile_with_shapes("D = A @ B", &shapes, &config).unwrap();

    println!("Tiling analysis:");
    println!("  - M tiles: {} (4 rows -> ceil(4/3) = 2)", 2);
    println!("  - N tiles: {} (8 cols -> ceil(8/3) = 3)", 3);
    println!("  - K tiles: {} (6 inner -> ceil(6/3) = 2)", 2);
    println!("  - Total passes: {} (2 * 3 * 2 = 12)", result.passes.len());
    println!();

    println!("Generated passes:");
    for pass in &result.passes {
        println!("  Pass {}: {}", pass.id, pass.description);
    }
    println!();

    println!("Output shape: {:?}", result.output_shape);
    println!("Total cycles: {}", result.total_cycles);
    println!();

    // Example 3: Using a larger array
    println!("\nExample 3: Same computation with 4x4 array");

    let config_4x4 = SystolicConfig::new(4, 8, 32);
    let shapes = vec![("A", (6, 6)), ("B", (6, 6))];
    let result = compile_with_shapes("E = A @ B", &shapes, &config_4x4).unwrap();

    println!("Tiling with 4x4 array:");
    println!("  - M tiles: {} (6 rows -> ceil(6/4) = 2)", 2);
    println!("  - N tiles: {} (6 cols -> ceil(6/4) = 2)", 2);
    println!("  - K tiles: {} (6 inner -> ceil(6/4) = 2)", 2);
    println!("  - Total passes: {} (2 * 2 * 2 = 8)", result.passes.len());
    println!("  - Cycles per pass: {}", config_4x4.cycles_for_matmul());
    println!("  - Total cycles: {}", result.total_cycles);
    println!();

    // Compare efficiency
    println!("Efficiency comparison for 6x6 @ 6x6:");
    println!("  3x3 array: 8 passes × 8 cycles = 64 cycles");
    println!("  4x4 array: 8 passes × 11 cycles = 88 cycles");
    println!("  (4x4 has fewer passes but more cycles per pass for this size)");
}
