//! Matrix tiling strategy for systolic array execution
//!
//! Handles matrices larger than the systolic array by breaking them
//! into tiles that can be processed sequentially.
//!
//! For C = A @ B where A is MxK and B is KxN:
//! - If M > array_size: tile along rows of A
//! - If N > array_size: tile along columns of B
//! - If K > array_size: accumulate partial products

use crate::ast::{TypedExpr, TypedExprKind, TypedProgram, TypedStatement};
use crate::error::{CompileError, CompileResult};
use crate::hardware::SystolicConfig;

/// Tiling strategy for large matrices
pub struct TilingStrategy {
    config: SystolicConfig,
}

impl TilingStrategy {
    pub fn new(config: SystolicConfig) -> Self {
        Self { config }
    }
    
    /// Tile a complete program
    pub fn tile_program(&self, program: &TypedProgram) -> CompileResult<TiledProgram> {
        let mut operations = Vec::new();
        
        for stmt in &program.statements {
            let tiled_ops = self.tile_statement(stmt)?;
            operations.extend(tiled_ops);
        }
        
        Ok(TiledProgram { operations })
    }
    
    /// Tile a single statement
    fn tile_statement(&self, stmt: &TypedStatement) -> CompileResult<Vec<TiledOperation>> {
        self.tile_expr(&stmt.value, &stmt.target)
    }
    
    /// Tile an expression recursively
    fn tile_expr(&self, expr: &TypedExpr, target: &str) -> CompileResult<Vec<TiledOperation>> {
        match &expr.expr {
            TypedExprKind::MatMul(left, right) => {
                self.tile_matmul(left, right, target)
            }
            TypedExprKind::Add(left, right) => {
                let mut ops = self.tile_expr(left, &format!("{}_add_left", target))?;
                ops.extend(self.tile_expr(right, &format!("{}_add_right", target))?);
                ops.push(TiledOperation::Add {
                    target: target.to_string(),
                    left: format!("{}_add_left", target),
                    right: format!("{}_add_right", target),
                    shape: expr.shape.dimensions().unwrap_or((0, 0)),
                });
                Ok(ops)
            }
            TypedExprKind::Sub(left, right) => {
                let mut ops = self.tile_expr(left, &format!("{}_sub_left", target))?;
                ops.extend(self.tile_expr(right, &format!("{}_sub_right", target))?);
                ops.push(TiledOperation::Sub {
                    target: target.to_string(),
                    left: format!("{}_sub_left", target),
                    right: format!("{}_sub_right", target),
                    shape: expr.shape.dimensions().unwrap_or((0, 0)),
                });
                Ok(ops)
            }
            TypedExprKind::Transpose(inner) => {
                let mut ops = self.tile_expr(inner, &format!("{}_transpose_inner", target))?;
                ops.push(TiledOperation::Transpose {
                    target: target.to_string(),
                    source: format!("{}_transpose_inner", target),
                    shape: expr.shape.dimensions().unwrap_or((0, 0)),
                });
                Ok(ops)
            }
            TypedExprKind::Variable(name) => {
                Ok(vec![TiledOperation::LoadMatrix {
                    target: target.to_string(),
                    source: name.clone(),
                    shape: expr.shape.dimensions().unwrap_or((0, 0)),
                }])
            }
            TypedExprKind::Matrix(data) => {
                let rows = data.len();
                let cols = if rows > 0 { data[0].len() } else { 0 };
                let flat: Vec<f64> = data.iter().flatten().copied().collect();
                Ok(vec![TiledOperation::LoadLiteral {
                    target: target.to_string(),
                    data: flat,
                    shape: (rows, cols),
                }])
            }
            TypedExprKind::Scalar(n) => {
                Ok(vec![TiledOperation::LoadLiteral {
                    target: target.to_string(),
                    data: vec![*n],
                    shape: (1, 1),
                }])
            }
            TypedExprKind::Mul(left, right) => {
                // Element-wise multiplication (not for systolic array)
                let mut ops = self.tile_expr(left, &format!("{}_mul_left", target))?;
                ops.extend(self.tile_expr(right, &format!("{}_mul_right", target))?);
                ops.push(TiledOperation::ElementMul {
                    target: target.to_string(),
                    left: format!("{}_mul_left", target),
                    right: format!("{}_mul_right", target),
                    shape: expr.shape.dimensions().unwrap_or((0, 0)),
                });
                Ok(ops)
            }
            TypedExprKind::ScalarMul(scalar, matrix) => {
                let mut ops = self.tile_expr(matrix, &format!("{}_smul_matrix", target))?;
                if let TypedExprKind::Scalar(s) = &scalar.expr {
                    ops.push(TiledOperation::ScalarMul {
                        target: target.to_string(),
                        source: format!("{}_smul_matrix", target),
                        scalar: *s,
                        shape: expr.shape.dimensions().unwrap_or((0, 0)),
                    });
                }
                Ok(ops)
            }
        }
    }
    
    /// Tile a matrix multiplication operation
    fn tile_matmul(
        &self,
        left: &TypedExpr,
        right: &TypedExpr,
        target: &str,
    ) -> CompileResult<Vec<TiledOperation>> {
        let (m, k1) = left.shape.dimensions()
            .ok_or_else(|| CompileError::tiling("Unknown left operand shape"))?;
        let (k2, n) = right.shape.dimensions()
            .ok_or_else(|| CompileError::tiling("Unknown right operand shape"))?;
        
        if k1 != k2 {
            return Err(CompileError::tiling(format!(
                "Inner dimensions must match: {} != {}",
                k1, k2
            )));
        }
        let k = k1;
        
        let tile_size = self.config.array_size;
        
        // Calculate number of tiles needed
        let m_tiles = (m + tile_size - 1) / tile_size;
        let n_tiles = (n + tile_size - 1) / tile_size;
        let k_tiles = (k + tile_size - 1) / tile_size;
        
        let mut operations = Vec::new();
        
        // First, process operands
        let left_ops = self.tile_expr(left, &format!("{}_left", target))?;
        let right_ops = self.tile_expr(right, &format!("{}_right", target))?;
        operations.extend(left_ops);
        operations.extend(right_ops);
        
        // Generate tiled matrix multiplication
        let mut tiles = Vec::new();
        
        for i in 0..m_tiles {
            for j in 0..n_tiles {
                for kk in 0..k_tiles {
                    let tile_m_start = i * tile_size;
                    let tile_m_end = ((i + 1) * tile_size).min(m);
                    let tile_n_start = j * tile_size;
                    let tile_n_end = ((j + 1) * tile_size).min(n);
                    let tile_k_start = kk * tile_size;
                    let tile_k_end = ((kk + 1) * tile_size).min(k);
                    
                    tiles.push(MatMulTile {
                        output_row: i,
                        output_col: j,
                        k_index: kk,
                        a_row_range: (tile_m_start, tile_m_end),
                        a_col_range: (tile_k_start, tile_k_end),
                        b_row_range: (tile_k_start, tile_k_end),
                        b_col_range: (tile_n_start, tile_n_end),
                        is_first_k: kk == 0,
                        is_last_k: kk == k_tiles - 1,
                    });
                }
            }
        }
        
        operations.push(TiledOperation::TiledMatMul {
            target: target.to_string(),
            left_source: format!("{}_left", target),
            right_source: format!("{}_right", target),
            left_shape: (m, k),
            right_shape: (k, n),
            output_shape: (m, n),
            tiles,
            tile_size,
        });
        
        Ok(operations)
    }
}

/// A tiled program ready for code generation
#[derive(Debug, Clone)]
pub struct TiledProgram {
    pub operations: Vec<TiledOperation>,
}

/// A single tiled operation
#[derive(Debug, Clone)]
pub enum TiledOperation {
    /// Load a matrix from memory/variable
    LoadMatrix {
        target: String,
        source: String,
        shape: (usize, usize),
    },
    /// Load a literal matrix
    LoadLiteral {
        target: String,
        data: Vec<f64>,
        shape: (usize, usize),
    },
    /// Tiled matrix multiplication
    TiledMatMul {
        target: String,
        left_source: String,
        right_source: String,
        left_shape: (usize, usize),
        right_shape: (usize, usize),
        output_shape: (usize, usize),
        tiles: Vec<MatMulTile>,
        tile_size: usize,
    },
    /// Element-wise addition
    Add {
        target: String,
        left: String,
        right: String,
        shape: (usize, usize),
    },
    /// Element-wise subtraction
    Sub {
        target: String,
        left: String,
        right: String,
        shape: (usize, usize),
    },
    /// Element-wise multiplication
    ElementMul {
        target: String,
        left: String,
        right: String,
        shape: (usize, usize),
    },
    /// Scalar multiplication
    ScalarMul {
        target: String,
        source: String,
        scalar: f64,
        shape: (usize, usize),
    },
    /// Transpose operation
    Transpose {
        target: String,
        source: String,
        shape: (usize, usize),
    },
}

/// Information about a single tile in a tiled matrix multiplication
#[derive(Debug, Clone)]
pub struct MatMulTile {
    /// Which tile of the output this contributes to (row)
    pub output_row: usize,
    /// Which tile of the output this contributes to (col)
    pub output_col: usize,
    /// K dimension tile index
    pub k_index: usize,
    /// Row range in matrix A
    pub a_row_range: (usize, usize),
    /// Column range in matrix A
    pub a_col_range: (usize, usize),
    /// Row range in matrix B
    pub b_row_range: (usize, usize),
    /// Column range in matrix B
    pub b_col_range: (usize, usize),
    /// Is this the first tile along K?
    pub is_first_k: bool,
    /// Is this the last tile along K?
    pub is_last_k: bool,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ast::Shape;
    
    #[test]
    fn test_small_matmul_no_tiling() {
        let config = SystolicConfig::new(3, 8, 32);
        let tiler = TilingStrategy::new(config);
        
        // 2x2 @ 2x2 - fits in 3x3 array
        let left = TypedExpr {
            expr: TypedExprKind::Variable("A".to_string()),
            shape: Shape::matrix(2, 2),
        };
        let right = TypedExpr {
            expr: TypedExprKind::Variable("B".to_string()),
            shape: Shape::matrix(2, 2),
        };
        
        let ops = tiler.tile_matmul(&left, &right, "C").unwrap();
        
        // Should have: LoadMatrix A, LoadMatrix B, TiledMatMul
        assert_eq!(ops.len(), 3);
        
        if let TiledOperation::TiledMatMul { tiles, .. } = &ops[2] {
            assert_eq!(tiles.len(), 1); // Single tile
        } else {
            panic!("Expected TiledMatMul");
        }
    }
    
    #[test]
    fn test_large_matmul_tiling() {
        let config = SystolicConfig::new(3, 8, 32);
        let tiler = TilingStrategy::new(config);
        
        // 6x6 @ 6x6 - needs 2x2 tiling in output, 2 along K
        let left = TypedExpr {
            expr: TypedExprKind::Variable("A".to_string()),
            shape: Shape::matrix(6, 6),
        };
        let right = TypedExpr {
            expr: TypedExprKind::Variable("B".to_string()),
            shape: Shape::matrix(6, 6),
        };
        
        let ops = tiler.tile_matmul(&left, &right, "C").unwrap();
        
        if let TiledOperation::TiledMatMul { tiles, .. } = &ops[2] {
            // 2 tiles in M, 2 tiles in N, 2 tiles in K = 8 tiles total
            assert_eq!(tiles.len(), 8);
        } else {
            panic!("Expected TiledMatMul");
        }
    }
}
