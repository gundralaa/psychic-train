//! Code generator for systolic array passes
//!
//! Converts tiled operations into sequences of systolic array passes.

use std::collections::HashMap;
use crate::error::CompileResult;
use crate::hardware::{
    HardwareProgram, PassOperation, SystolicConfig, SystolicPass, TileCoord,
    pad_matrix, quantize_matrix, row_to_column_major,
};
use crate::tiling::{MatMulTile, TiledOperation, TiledProgram};

/// Code generator for systolic array hardware
pub struct CodeGenerator {
    config: SystolicConfig,
    pass_counter: usize,
    /// Storage for matrix data
    matrix_data: HashMap<String, MatrixData>,
}

/// Stored matrix data
#[derive(Debug, Clone)]
#[allow(dead_code)]
struct MatrixData {
    data: Vec<f64>,
    shape: (usize, usize),
}

impl CodeGenerator {
    pub fn new(config: SystolicConfig) -> Self {
        Self {
            config,
            pass_counter: 0,
            matrix_data: HashMap::new(),
        }
    }
    
    /// Generate hardware program from tiled operations
    pub fn generate(&mut self, program: TiledProgram) -> CompileResult<HardwareProgram> {
        let mut hw_program = HardwareProgram::new(self.config.clone());
        
        for op in &program.operations {
            self.process_operation(op, &mut hw_program)?;
        }
        
        hw_program.generate_summary();
        Ok(hw_program)
    }
    
    /// Process a single tiled operation
    fn process_operation(
        &mut self,
        op: &TiledOperation,
        program: &mut HardwareProgram,
    ) -> CompileResult<()> {
        match op {
            TiledOperation::LoadMatrix { target, source, shape } => {
                // Reference to existing matrix - copy the reference
                if let Some(data) = self.matrix_data.get(source) {
                    self.matrix_data.insert(target.clone(), data.clone());
                } else {
                    // Placeholder - actual data will come from external source
                    self.matrix_data.insert(target.clone(), MatrixData {
                        data: vec![0.0; shape.0 * shape.1],
                        shape: *shape,
                    });
                }
                Ok(())
            }
            
            TiledOperation::LoadLiteral { target, data, shape } => {
                self.matrix_data.insert(target.clone(), MatrixData {
                    data: data.clone(),
                    shape: *shape,
                });
                Ok(())
            }
            
            TiledOperation::TiledMatMul {
                target,
                left_source,
                right_source,
                left_shape,
                right_shape,
                output_shape,
                tiles,
                tile_size,
            } => {
                self.generate_tiled_matmul(
                    program,
                    target,
                    left_source,
                    right_source,
                    *left_shape,
                    *right_shape,
                    *output_shape,
                    tiles,
                    *tile_size,
                )
            }
            
            TiledOperation::Add { target, shape, .. } |
            TiledOperation::Sub { target, shape, .. } |
            TiledOperation::ElementMul { target, shape, .. } |
            TiledOperation::ScalarMul { target, shape, .. } |
            TiledOperation::Transpose { target, shape, .. } => {
                // These operations are handled outside the systolic array
                // Just track the output shape
                self.matrix_data.insert(target.clone(), MatrixData {
                    data: vec![0.0; shape.0 * shape.1],
                    shape: *shape,
                });
                program.output_shape = *shape;
                Ok(())
            }
        }
    }
    
    /// Generate passes for a tiled matrix multiplication
    fn generate_tiled_matmul(
        &mut self,
        program: &mut HardwareProgram,
        target: &str,
        left_source: &str,
        right_source: &str,
        left_shape: (usize, usize),
        right_shape: (usize, usize),
        output_shape: (usize, usize),
        tiles: &[MatMulTile],
        tile_size: usize,
    ) -> CompileResult<()> {
        let left_data = self.matrix_data.get(left_source)
            .map(|d| d.data.clone())
            .unwrap_or_else(|| vec![0.0; left_shape.0 * left_shape.1]);
        
        let right_data = self.matrix_data.get(right_source)
            .map(|d| d.data.clone())
            .unwrap_or_else(|| vec![0.0; right_shape.0 * right_shape.1]);
        
        program.output_shape = output_shape;
        
        for tile in tiles {
            let pass = self.generate_matmul_pass(
                tile,
                &left_data,
                left_shape,
                &right_data,
                right_shape,
                tile_size,
            )?;
            program.add_pass(pass);
        }
        
        // Store placeholder for output
        self.matrix_data.insert(target.to_string(), MatrixData {
            data: vec![0.0; output_shape.0 * output_shape.1],
            shape: output_shape,
        });
        
        Ok(())
    }
    
    /// Generate a single systolic array pass
    fn generate_matmul_pass(
        &mut self,
        tile: &MatMulTile,
        left_data: &[f64],
        left_shape: (usize, usize),
        right_data: &[f64],
        right_shape: (usize, usize),
        tile_size: usize,
    ) -> CompileResult<SystolicPass> {
        let pass_id = self.pass_counter;
        self.pass_counter += 1;
        
        // Extract tile from matrix A
        let a_rows = tile.a_row_range.1 - tile.a_row_range.0;
        let a_cols = tile.a_col_range.1 - tile.a_col_range.0;
        let mut a_tile = Vec::with_capacity(a_rows * a_cols);
        
        for i in tile.a_row_range.0..tile.a_row_range.1 {
            for j in tile.a_col_range.0..tile.a_col_range.1 {
                let idx = i * left_shape.1 + j;
                a_tile.push(if idx < left_data.len() { left_data[idx] } else { 0.0 });
            }
        }
        
        // Extract tile from matrix B
        let b_rows = tile.b_row_range.1 - tile.b_row_range.0;
        let b_cols = tile.b_col_range.1 - tile.b_col_range.0;
        let mut b_tile = Vec::with_capacity(b_rows * b_cols);
        
        for i in tile.b_row_range.0..tile.b_row_range.1 {
            for j in tile.b_col_range.0..tile.b_col_range.1 {
                let idx = i * right_shape.1 + j;
                b_tile.push(if idx < right_data.len() { right_data[idx] } else { 0.0 });
            }
        }
        
        // Pad tiles to array size
        let padded_a = pad_matrix(
            &quantize_matrix(&a_tile, 1.0, &self.config),
            a_rows, a_cols,
            tile_size, tile_size,
        );
        
        let padded_b_row_major = pad_matrix(
            &quantize_matrix(&b_tile, 1.0, &self.config),
            b_rows, b_cols,
            tile_size, tile_size,
        );
        
        // Convert B to column-major for hardware
        let padded_b = row_to_column_major(&padded_b_row_major, tile_size, tile_size);
        
        let operation = if tile.is_first_k && tile.is_last_k {
            PassOperation::Final
        } else if tile.is_first_k {
            PassOperation::Initialize
        } else if tile.is_last_k {
            PassOperation::Final
        } else {
            PassOperation::Accumulate
        };
        
        Ok(SystolicPass {
            id: pass_id,
            description: format!(
                "C[{}:{}, {}:{}] += A[{}:{}, {}:{}] @ B[{}:{}, {}:{}]",
                tile.output_row * tile_size,
                (tile.output_row + 1) * tile_size,
                tile.output_col * tile_size,
                (tile.output_col + 1) * tile_size,
                tile.a_row_range.0, tile.a_row_range.1,
                tile.a_col_range.0, tile.a_col_range.1,
                tile.b_row_range.0, tile.b_row_range.1,
                tile.b_col_range.0, tile.b_col_range.1,
            ),
            matrix_a: padded_a,
            a_shape: (a_rows, a_cols),
            matrix_b: padded_b,
            b_shape: (b_rows, b_cols),
            output_shape: (tile_size.min(a_rows), tile_size.min(b_cols)),
            output_tile: TileCoord::new(
                tile.output_row,
                tile.output_col,
                tile.output_row * tile_size,
                tile.output_col * tile_size,
            ),
            operation,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tiling::TilingStrategy;
    use crate::analyzer::Analyzer;
    use crate::parser::Parser;
    
    #[test]
    fn test_simple_codegen() {
        let config = SystolicConfig::new(3, 8, 32);
        
        // Parse and analyze
        let mut parser = Parser::new("C = A @ B");
        let program = parser.parse_program().unwrap();
        
        let mut analyzer = Analyzer::new();
        analyzer.define_matrix("A", (2, 2));
        analyzer.define_matrix("B", (2, 2));
        let typed = analyzer.analyze(program).unwrap();
        
        // Tile
        let tiler = TilingStrategy::new(config.clone());
        let tiled = tiler.tile_program(&typed).unwrap();
        
        // Generate
        let mut codegen = CodeGenerator::new(config);
        let hw_program = codegen.generate(tiled).unwrap();
        
        assert_eq!(hw_program.passes.len(), 1);
        assert_eq!(hw_program.output_shape, (2, 2));
    }
    
    #[test]
    fn test_literal_matrix_codegen() {
        let config = SystolicConfig::new(3, 8, 32);
        
        // Parse with literal matrices
        let mut parser = Parser::new("C = [[1, 2], [3, 4]] @ [[5, 6], [7, 8]]");
        let program = parser.parse_program().unwrap();
        
        let mut analyzer = Analyzer::new();
        let typed = analyzer.analyze(program).unwrap();
        
        let tiler = TilingStrategy::new(config.clone());
        let tiled = tiler.tile_program(&typed).unwrap();
        
        let mut codegen = CodeGenerator::new(config);
        let hw_program = codegen.generate(tiled).unwrap();
        
        assert_eq!(hw_program.passes.len(), 1);
        
        // Check matrix A data (padded to 3x3)
        // Original: [1, 2, 3, 4] -> padded: [1, 2, 0, 3, 4, 0, 0, 0, 0]
        let pass = &hw_program.passes[0];
        assert_eq!(pass.matrix_a[0], 1);
        assert_eq!(pass.matrix_a[1], 2);
        assert_eq!(pass.matrix_a[3], 3);
        assert_eq!(pass.matrix_a[4], 4);
    }
}
