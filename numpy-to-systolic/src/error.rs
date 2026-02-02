//! Error types for the NumPy to Systolic compiler

use thiserror::Error;

/// Result type for compilation operations
pub type CompileResult<T> = Result<T, CompileError>;

/// Compilation errors
#[derive(Debug, Error)]
pub enum CompileError {
    #[error("Lexer error at position {position}: {message}")]
    LexerError { position: usize, message: String },

    #[error("Parser error: {message}")]
    ParseError { message: String },

    #[error("Type error: {message}")]
    TypeError { message: String },

    #[error("Shape mismatch: expected {expected}, got {got}")]
    ShapeMismatch { expected: String, got: String },

    #[error("Undefined variable: {name}")]
    UndefinedVariable { name: String },

    #[error("Invalid operation: {message}")]
    InvalidOperation { message: String },

    #[error("Tiling error: {message}")]
    TilingError { message: String },

    #[error("Code generation error: {message}")]
    CodeGenError { message: String },
}

impl CompileError {
    pub fn parse_error(msg: impl Into<String>) -> Self {
        CompileError::ParseError { message: msg.into() }
    }

    pub fn type_error(msg: impl Into<String>) -> Self {
        CompileError::TypeError { message: msg.into() }
    }

    pub fn undefined(name: impl Into<String>) -> Self {
        CompileError::UndefinedVariable { name: name.into() }
    }

    pub fn invalid_op(msg: impl Into<String>) -> Self {
        CompileError::InvalidOperation { message: msg.into() }
    }

    pub fn tiling(msg: impl Into<String>) -> Self {
        CompileError::TilingError { message: msg.into() }
    }

    pub fn codegen(msg: impl Into<String>) -> Self {
        CompileError::CodeGenError { message: msg.into() }
    }
}
