//! Abstract Syntax Tree definitions for NumPy expressions

use serde::{Deserialize, Serialize};

/// A complete program consisting of statements
#[derive(Debug, Clone, PartialEq)]
pub struct Program {
    pub statements: Vec<Statement>,
}

/// A statement in the program
#[derive(Debug, Clone, PartialEq)]
pub enum Statement {
    /// Variable assignment: `X = expr`
    Assignment { target: String, value: Expr },
    /// Expression statement (for evaluation)
    Expression(Expr),
}

/// Expression types
#[derive(Debug, Clone, PartialEq)]
pub enum Expr {
    /// Matrix variable reference
    Variable(String),
    
    /// Scalar literal
    Scalar(f64),
    
    /// Matrix literal: `[[1, 2], [3, 4]]`
    Matrix(MatrixLiteral),
    
    /// Matrix multiplication: `A @ B`
    MatMul(Box<Expr>, Box<Expr>),
    
    /// Element-wise addition: `A + B`
    Add(Box<Expr>, Box<Expr>),
    
    /// Element-wise subtraction: `A - B`
    Sub(Box<Expr>, Box<Expr>),
    
    /// Element-wise multiplication: `A * B`
    Mul(Box<Expr>, Box<Expr>),
    
    /// Scalar multiplication: `scalar * A`
    ScalarMul(Box<Expr>, Box<Expr>),
    
    /// Matrix transpose: `A.T` or `np.transpose(A)`
    Transpose(Box<Expr>),
    
    /// Function call: `np.zeros((m, n))`, `np.eye(n)`, etc.
    FunctionCall { name: String, args: Vec<Expr> },
    
    /// Tuple literal for shapes: `(3, 4)`
    Tuple(Vec<Expr>),
}

/// A matrix literal value
#[derive(Debug, Clone, PartialEq)]
pub struct MatrixLiteral {
    pub rows: Vec<Vec<f64>>,
}

impl MatrixLiteral {
    pub fn new(rows: Vec<Vec<f64>>) -> Self {
        Self { rows }
    }
    
    pub fn shape(&self) -> (usize, usize) {
        let m = self.rows.len();
        let n = if m > 0 { self.rows[0].len() } else { 0 };
        (m, n)
    }
    
    /// Flatten matrix to row-major order
    pub fn flatten_row_major(&self) -> Vec<f64> {
        self.rows.iter().flatten().copied().collect()
    }
    
    /// Flatten matrix to column-major order
    pub fn flatten_column_major(&self) -> Vec<f64> {
        let (m, n) = self.shape();
        let mut result = Vec::with_capacity(m * n);
        for j in 0..n {
            for row in &self.rows {
                if j < row.len() {
                    result.push(row[j]);
                }
            }
        }
        result
    }
}

/// Typed expression with inferred shape information
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TypedExpr {
    pub expr: TypedExprKind,
    pub shape: Shape,
}

/// Shape of a matrix
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Shape {
    Scalar,
    Matrix { rows: usize, cols: usize },
    Unknown,
}

impl Shape {
    pub fn matrix(rows: usize, cols: usize) -> Self {
        Shape::Matrix { rows, cols }
    }
    
    pub fn is_matrix(&self) -> bool {
        matches!(self, Shape::Matrix { .. })
    }
    
    pub fn dimensions(&self) -> Option<(usize, usize)> {
        match self {
            Shape::Matrix { rows, cols } => Some((*rows, *cols)),
            Shape::Scalar => Some((1, 1)),
            Shape::Unknown => None,
        }
    }
}

impl std::fmt::Display for Shape {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Shape::Scalar => write!(f, "scalar"),
            Shape::Matrix { rows, cols } => write!(f, "({}, {})", rows, cols),
            Shape::Unknown => write!(f, "unknown"),
        }
    }
}

/// Typed expression kinds
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum TypedExprKind {
    Variable(String),
    Scalar(f64),
    Matrix(Vec<Vec<f64>>),
    MatMul(Box<TypedExpr>, Box<TypedExpr>),
    Add(Box<TypedExpr>, Box<TypedExpr>),
    Sub(Box<TypedExpr>, Box<TypedExpr>),
    Mul(Box<TypedExpr>, Box<TypedExpr>),
    ScalarMul(Box<TypedExpr>, Box<TypedExpr>),
    Transpose(Box<TypedExpr>),
}

/// A typed statement
#[derive(Debug, Clone, PartialEq)]
pub struct TypedStatement {
    pub target: String,
    pub value: TypedExpr,
}

/// A typed program
#[derive(Debug, Clone, PartialEq)]
pub struct TypedProgram {
    pub statements: Vec<TypedStatement>,
}
