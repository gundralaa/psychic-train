//! Type analyzer for inferring matrix shapes
//!
//! Performs shape inference and type checking on the AST.

use std::collections::HashMap;
use crate::ast::*;
use crate::error::{CompileError, CompileResult};

/// Analyzer for type checking and shape inference
pub struct Analyzer {
    /// Known matrix shapes: name -> (rows, cols)
    shapes: HashMap<String, (usize, usize)>,
}

impl Analyzer {
    pub fn new() -> Self {
        Self {
            shapes: HashMap::new(),
        }
    }
    
    /// Define a matrix with known shape
    pub fn define_matrix(&mut self, name: &str, shape: (usize, usize)) {
        self.shapes.insert(name.to_string(), shape);
    }
    
    /// Analyze a program and produce typed AST
    pub fn analyze(&mut self, program: Program) -> CompileResult<TypedProgram> {
        let mut statements = Vec::new();
        
        for stmt in program.statements {
            statements.push(self.analyze_statement(stmt)?);
        }
        
        Ok(TypedProgram { statements })
    }
    
    /// Analyze a statement
    fn analyze_statement(&mut self, stmt: Statement) -> CompileResult<TypedStatement> {
        match stmt {
            Statement::Assignment { target, value } => {
                let typed_value = self.analyze_expr(&value)?;
                
                // Record the shape of the target variable
                if let Some((rows, cols)) = typed_value.shape.dimensions() {
                    self.shapes.insert(target.clone(), (rows, cols));
                }
                
                Ok(TypedStatement {
                    target,
                    value: typed_value,
                })
            }
            Statement::Expression(expr) => {
                let typed_value = self.analyze_expr(&expr)?;
                Ok(TypedStatement {
                    target: "_".to_string(),
                    value: typed_value,
                })
            }
        }
    }
    
    /// Analyze an expression and infer its shape
    fn analyze_expr(&mut self, expr: &Expr) -> CompileResult<TypedExpr> {
        match expr {
            Expr::Variable(name) => {
                let shape = self.shapes.get(name)
                    .map(|(r, c)| Shape::matrix(*r, *c))
                    .unwrap_or(Shape::Unknown);
                
                Ok(TypedExpr {
                    expr: TypedExprKind::Variable(name.clone()),
                    shape,
                })
            }
            
            Expr::Scalar(n) => {
                Ok(TypedExpr {
                    expr: TypedExprKind::Scalar(*n),
                    shape: Shape::Scalar,
                })
            }
            
            Expr::Matrix(mat) => {
                let shape = mat.shape();
                Ok(TypedExpr {
                    expr: TypedExprKind::Matrix(mat.rows.clone()),
                    shape: Shape::matrix(shape.0, shape.1),
                })
            }
            
            Expr::MatMul(left, right) => {
                let left_typed = self.analyze_expr(left)?;
                let right_typed = self.analyze_expr(right)?;
                
                // Check dimension compatibility
                let shape = match (&left_typed.shape, &right_typed.shape) {
                    (Shape::Matrix { rows: m, cols: k1 }, Shape::Matrix { rows: k2, cols: n }) => {
                        if k1 != k2 {
                            return Err(CompileError::ShapeMismatch {
                                expected: format!("inner dimensions to match, got {} and {}", k1, k2),
                                got: format!("left: ({}, {}), right: ({}, {})", m, k1, k2, n),
                            });
                        }
                        Shape::matrix(*m, *n)
                    }
                    (Shape::Unknown, _) | (_, Shape::Unknown) => Shape::Unknown,
                    _ => return Err(CompileError::type_error("MatMul requires matrix operands")),
                };
                
                Ok(TypedExpr {
                    expr: TypedExprKind::MatMul(Box::new(left_typed), Box::new(right_typed)),
                    shape,
                })
            }
            
            Expr::Add(left, right) => {
                let left_typed = self.analyze_expr(left)?;
                let right_typed = self.analyze_expr(right)?;
                
                let shape = self.check_broadcast_compatible(&left_typed.shape, &right_typed.shape)?;
                
                Ok(TypedExpr {
                    expr: TypedExprKind::Add(Box::new(left_typed), Box::new(right_typed)),
                    shape,
                })
            }
            
            Expr::Sub(left, right) => {
                let left_typed = self.analyze_expr(left)?;
                let right_typed = self.analyze_expr(right)?;
                
                let shape = self.check_broadcast_compatible(&left_typed.shape, &right_typed.shape)?;
                
                Ok(TypedExpr {
                    expr: TypedExprKind::Sub(Box::new(left_typed), Box::new(right_typed)),
                    shape,
                })
            }
            
            Expr::Mul(left, right) => {
                let left_typed = self.analyze_expr(left)?;
                let right_typed = self.analyze_expr(right)?;
                
                // Element-wise or scalar multiplication
                let shape = match (&left_typed.shape, &right_typed.shape) {
                    (Shape::Scalar, other) | (other, Shape::Scalar) => other.clone(),
                    (Shape::Matrix { rows: r1, cols: c1 }, Shape::Matrix { rows: r2, cols: c2 }) => {
                        if r1 != r2 || c1 != c2 {
                            return Err(CompileError::ShapeMismatch {
                                expected: format!("same shape for element-wise mul"),
                                got: format!("({}, {}) and ({}, {})", r1, c1, r2, c2),
                            });
                        }
                        Shape::matrix(*r1, *c1)
                    }
                    (Shape::Unknown, _) | (_, Shape::Unknown) => Shape::Unknown,
                };
                
                Ok(TypedExpr {
                    expr: TypedExprKind::Mul(Box::new(left_typed), Box::new(right_typed)),
                    shape,
                })
            }
            
            Expr::ScalarMul(scalar, matrix) => {
                let scalar_typed = self.analyze_expr(scalar)?;
                let matrix_typed = self.analyze_expr(matrix)?;
                let shape = matrix_typed.shape.clone();
                
                Ok(TypedExpr {
                    expr: TypedExprKind::ScalarMul(Box::new(scalar_typed), Box::new(matrix_typed)),
                    shape,
                })
            }
            
            Expr::Transpose(inner) => {
                let inner_typed = self.analyze_expr(inner)?;
                
                let shape = match &inner_typed.shape {
                    Shape::Matrix { rows, cols } => Shape::matrix(*cols, *rows),
                    Shape::Scalar => Shape::Scalar,
                    Shape::Unknown => Shape::Unknown,
                };
                
                Ok(TypedExpr {
                    expr: TypedExprKind::Transpose(Box::new(inner_typed)),
                    shape,
                })
            }
            
            Expr::FunctionCall { name, args } => {
                self.analyze_function_call(name, args)
            }
            
            Expr::Tuple(elements) => {
                // Tuples are usually for specifying shapes
                // Extract numeric values for shape
                if elements.len() == 2 {
                    if let (Expr::Scalar(rows), Expr::Scalar(cols)) = (&elements[0], &elements[1]) {
                        let r = *rows as usize;
                        let c = *cols as usize;
                        return Ok(TypedExpr {
                            expr: TypedExprKind::Scalar(0.0), // Placeholder
                            shape: Shape::matrix(r, c),
                        });
                    }
                }
                Err(CompileError::type_error("Invalid tuple expression"))
            }
        }
    }
    
    /// Analyze a numpy function call
    fn analyze_function_call(&mut self, name: &str, args: &[Expr]) -> CompileResult<TypedExpr> {
        match name {
            "np.zeros" | "np.ones" | "np.empty" => {
                if args.len() != 1 {
                    return Err(CompileError::type_error(format!(
                        "{} expects 1 argument (shape tuple), got {}",
                        name, args.len()
                    )));
                }
                
                let shape = self.extract_shape(&args[0])?;
                Ok(TypedExpr {
                    expr: TypedExprKind::Matrix(vec![vec![0.0; shape.1]; shape.0]),
                    shape: Shape::matrix(shape.0, shape.1),
                })
            }
            
            "np.eye" | "np.identity" => {
                if args.is_empty() {
                    return Err(CompileError::type_error(format!(
                        "{} expects at least 1 argument",
                        name
                    )));
                }
                
                let n = self.extract_number(&args[0])? as usize;
                let mut matrix = vec![vec![0.0; n]; n];
                for i in 0..n {
                    matrix[i][i] = 1.0;
                }
                
                Ok(TypedExpr {
                    expr: TypedExprKind::Matrix(matrix),
                    shape: Shape::matrix(n, n),
                })
            }
            
            "np.transpose" => {
                if args.len() != 1 {
                    return Err(CompileError::type_error("np.transpose expects 1 argument"));
                }
                
                let inner = self.analyze_expr(&args[0])?;
                let shape = match &inner.shape {
                    Shape::Matrix { rows, cols } => Shape::matrix(*cols, *rows),
                    _ => Shape::Unknown,
                };
                
                Ok(TypedExpr {
                    expr: TypedExprKind::Transpose(Box::new(inner)),
                    shape,
                })
            }
            
            "np.matmul" | "np.dot" => {
                if args.len() != 2 {
                    return Err(CompileError::type_error(format!(
                        "{} expects 2 arguments",
                        name
                    )));
                }
                
                let left = self.analyze_expr(&args[0])?;
                let right = self.analyze_expr(&args[1])?;
                
                let shape = match (&left.shape, &right.shape) {
                    (Shape::Matrix { rows: m, cols: k1 }, Shape::Matrix { rows: k2, cols: n }) => {
                        if k1 != k2 {
                            return Err(CompileError::ShapeMismatch {
                                expected: format!("inner dimensions to match"),
                                got: format!("{} != {}", k1, k2),
                            });
                        }
                        Shape::matrix(*m, *n)
                    }
                    _ => Shape::Unknown,
                };
                
                Ok(TypedExpr {
                    expr: TypedExprKind::MatMul(Box::new(left), Box::new(right)),
                    shape,
                })
            }
            
            _ => Err(CompileError::type_error(format!("Unknown function: {}", name))),
        }
    }
    
    /// Extract a shape tuple from an expression
    fn extract_shape(&self, expr: &Expr) -> CompileResult<(usize, usize)> {
        match expr {
            Expr::Tuple(elements) if elements.len() == 2 => {
                let rows = self.extract_number(&elements[0])? as usize;
                let cols = self.extract_number(&elements[1])? as usize;
                Ok((rows, cols))
            }
            _ => Err(CompileError::type_error("Expected shape tuple (rows, cols)")),
        }
    }
    
    /// Extract a numeric value from an expression
    fn extract_number(&self, expr: &Expr) -> CompileResult<f64> {
        match expr {
            Expr::Scalar(n) => Ok(*n),
            _ => Err(CompileError::type_error("Expected numeric value")),
        }
    }
    
    /// Check if two shapes are broadcast compatible
    fn check_broadcast_compatible(&self, left: &Shape, right: &Shape) -> CompileResult<Shape> {
        match (left, right) {
            (Shape::Scalar, other) | (other, Shape::Scalar) => Ok(other.clone()),
            (Shape::Matrix { rows: r1, cols: c1 }, Shape::Matrix { rows: r2, cols: c2 }) => {
                if r1 == r2 && c1 == c2 {
                    Ok(Shape::matrix(*r1, *c1))
                } else {
                    Err(CompileError::ShapeMismatch {
                        expected: format!("matching shapes for broadcast"),
                        got: format!("({}, {}) and ({}, {})", r1, c1, r2, c2),
                    })
                }
            }
            (Shape::Unknown, other) | (other, Shape::Unknown) => Ok(other.clone()),
        }
    }
}

impl Default for Analyzer {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::Parser;
    
    #[test]
    fn test_analyze_matmul() {
        let mut parser = Parser::new("C = A @ B");
        let program = parser.parse_program().unwrap();
        
        let mut analyzer = Analyzer::new();
        analyzer.define_matrix("A", (2, 3));
        analyzer.define_matrix("B", (3, 4));
        
        let typed = analyzer.analyze(program).unwrap();
        
        assert_eq!(typed.statements.len(), 1);
        let stmt = &typed.statements[0];
        assert_eq!(stmt.target, "C");
        assert_eq!(stmt.value.shape, Shape::matrix(2, 4));
    }
    
    #[test]
    fn test_analyze_matmul_error() {
        let mut parser = Parser::new("C = A @ B");
        let program = parser.parse_program().unwrap();
        
        let mut analyzer = Analyzer::new();
        analyzer.define_matrix("A", (2, 3));
        analyzer.define_matrix("B", (4, 5)); // Incompatible dimensions
        
        let result = analyzer.analyze(program);
        assert!(result.is_err());
    }
    
    #[test]
    fn test_analyze_transpose() {
        let mut parser = Parser::new("B = A.T");
        let program = parser.parse_program().unwrap();
        
        let mut analyzer = Analyzer::new();
        analyzer.define_matrix("A", (2, 3));
        
        let typed = analyzer.analyze(program).unwrap();
        
        assert_eq!(typed.statements[0].value.shape, Shape::matrix(3, 2));
    }
}
