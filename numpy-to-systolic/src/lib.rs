//! NumPy to Systolic Array Compiler
//!
//! This library provides a compiler that takes NumPy-style matrix expressions
//! and converts them into a sequence of passes through a systolic array.
//!
//! # Example
//!
//! ```rust
//! use numpy_to_systolic::{compile_with_shapes, SystolicConfig};
//!
//! let config = SystolicConfig::new(3, 8, 32); // 3x3 array, 8-bit data, 32-bit accum
//! let shapes = vec![("A", (2, 3)), ("B", (3, 2))];
//! let result = compile_with_shapes("C = A @ B", &shapes, &config).unwrap();
//! println!("{}", result);
//! ```

pub mod ast;
pub mod lexer;
pub mod parser;
pub mod analyzer;
pub mod tiling;
pub mod codegen;
pub mod hardware;
pub mod error;

pub use ast::*;
pub use parser::Parser;
pub use analyzer::Analyzer;
pub use tiling::TilingStrategy;
pub use codegen::CodeGenerator;
pub use hardware::{SystolicConfig, SystolicPass, HardwareProgram};
pub use error::{CompileError, CompileResult};

/// Main compilation function that takes a NumPy expression and produces hardware instructions
pub fn compile(source: &str, config: &SystolicConfig) -> CompileResult<HardwareProgram> {
    // Parse the expression
    let mut parser = Parser::new(source);
    let program = parser.parse_program()?;
    
    // Analyze and infer shapes
    let mut analyzer = Analyzer::new();
    let typed_program = analyzer.analyze(program)?;
    
    // Generate tiling strategy
    let tiler = TilingStrategy::new(config.clone());
    let tiled_ops = tiler.tile_program(&typed_program)?;
    
    // Generate hardware instructions
    let mut codegen = CodeGenerator::new(config.clone());
    let hardware_program = codegen.generate(tiled_ops)?;
    
    Ok(hardware_program)
}

/// Compile with explicit matrix dimensions
pub fn compile_with_shapes(
    source: &str,
    shapes: &[(&str, (usize, usize))],
    config: &SystolicConfig,
) -> CompileResult<HardwareProgram> {
    let mut parser = Parser::new(source);
    let program = parser.parse_program()?;
    
    let mut analyzer = Analyzer::new();
    for (name, shape) in shapes {
        analyzer.define_matrix(name, *shape);
    }
    let typed_program = analyzer.analyze(program)?;
    
    let tiler = TilingStrategy::new(config.clone());
    let tiled_ops = tiler.tile_program(&typed_program)?;
    
    let mut codegen = CodeGenerator::new(config.clone());
    let hardware_program = codegen.generate(tiled_ops)?;
    
    Ok(hardware_program)
}
