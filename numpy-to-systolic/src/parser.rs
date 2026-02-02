//! Parser for NumPy-style expressions
//!
//! Parses expressions like:
//! - `C = A @ B`
//! - `D = A @ B + C`
//! - `E = np.transpose(A) @ B`
//! - `F = [[1, 2], [3, 4]] @ G`

use crate::ast::{Expr, MatrixLiteral, Program, Statement};
use crate::error::{CompileError, CompileResult};
use crate::lexer::{Lexer, Token};

/// Parser for NumPy expressions
pub struct Parser<'source> {
    lexer: Lexer<'source>,
    current: Option<Token>,
}

impl<'source> Parser<'source> {
    pub fn new(source: &'source str) -> Self {
        let mut lexer = Lexer::new(source);
        let current = lexer.next().and_then(Result::ok);
        Self { lexer, current }
    }
    
    /// Advance to the next token
    fn advance(&mut self) -> Option<Token> {
        let prev = self.current.take();
        self.current = self.lexer.next().and_then(Result::ok);
        prev
    }
    
    /// Check if current token matches expected
    fn check(&self, expected: &Token) -> bool {
        match &self.current {
            Some(tok) => std::mem::discriminant(tok) == std::mem::discriminant(expected),
            None => false,
        }
    }
    
    /// Consume token if it matches, otherwise error
    fn expect(&mut self, expected: Token) -> CompileResult<Token> {
        if self.check(&expected) {
            Ok(self.advance().unwrap())
        } else {
            Err(CompileError::parse_error(format!(
                "Expected {:?}, got {:?}",
                expected, self.current
            )))
        }
    }
    
    /// Parse a complete program (multiple statements)
    pub fn parse_program(&mut self) -> CompileResult<Program> {
        let mut statements = Vec::new();
        
        while self.current.is_some() {
            statements.push(self.parse_statement()?);
            
            // Optional semicolon between statements
            if self.check(&Token::Semicolon) {
                self.advance();
            }
        }
        
        Ok(Program { statements })
    }
    
    /// Parse a single statement
    fn parse_statement(&mut self) -> CompileResult<Statement> {
        // Check for assignment: identifier = expr
        if let Some(Token::Ident(name)) = &self.current {
            let name = name.clone();
            self.advance();
            
            if self.check(&Token::Equals) {
                self.advance();
                let value = self.parse_expr()?;
                return Ok(Statement::Assignment { target: name, value });
            } else {
                // Not an assignment, put the identifier back as an expression
                let expr = self.parse_expr_with_prefix(Expr::Variable(name))?;
                return Ok(Statement::Expression(expr));
            }
        }
        
        // Otherwise, it's an expression statement
        let expr = self.parse_expr()?;
        Ok(Statement::Expression(expr))
    }
    
    /// Parse an expression (handles operator precedence)
    pub fn parse_expr(&mut self) -> CompileResult<Expr> {
        self.parse_additive()
    }
    
    /// Continue parsing an expression with a prefix already parsed
    fn parse_expr_with_prefix(&mut self, prefix: Expr) -> CompileResult<Expr> {
        let prefix = self.parse_postfix_with_prefix(prefix)?;
        self.parse_matmul_with_prefix(prefix)
    }
    
    /// Parse additive expressions: a + b, a - b
    fn parse_additive(&mut self) -> CompileResult<Expr> {
        let mut left = self.parse_multiplicative()?;
        
        loop {
            if self.check(&Token::Plus) {
                self.advance();
                let right = self.parse_multiplicative()?;
                left = Expr::Add(Box::new(left), Box::new(right));
            } else if self.check(&Token::Minus) {
                self.advance();
                let right = self.parse_multiplicative()?;
                left = Expr::Sub(Box::new(left), Box::new(right));
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    /// Parse multiplicative expressions: a * b, a / b
    fn parse_multiplicative(&mut self) -> CompileResult<Expr> {
        let mut left = self.parse_matmul()?;
        
        loop {
            if self.check(&Token::Star) {
                self.advance();
                let right = self.parse_matmul()?;
                left = Expr::Mul(Box::new(left), Box::new(right));
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    /// Parse matrix multiplication: a @ b
    fn parse_matmul(&mut self) -> CompileResult<Expr> {
        let left = self.parse_unary()?;
        self.parse_matmul_with_prefix(left)
    }
    
    fn parse_matmul_with_prefix(&mut self, mut left: Expr) -> CompileResult<Expr> {
        while self.check(&Token::MatMul) {
            self.advance();
            let right = self.parse_unary()?;
            left = Expr::MatMul(Box::new(left), Box::new(right));
        }
        Ok(left)
    }
    
    /// Parse unary expressions: -a
    fn parse_unary(&mut self) -> CompileResult<Expr> {
        if self.check(&Token::Minus) {
            self.advance();
            let operand = self.parse_unary()?;
            Ok(Expr::ScalarMul(Box::new(Expr::Scalar(-1.0)), Box::new(operand)))
        } else {
            self.parse_postfix()
        }
    }
    
    /// Parse postfix expressions: a.T, a.method()
    fn parse_postfix(&mut self) -> CompileResult<Expr> {
        let primary = self.parse_primary()?;
        self.parse_postfix_with_prefix(primary)
    }
    
    fn parse_postfix_with_prefix(&mut self, mut expr: Expr) -> CompileResult<Expr> {
        loop {
            if self.check(&Token::Dot) {
                self.advance();
                
                if let Some(Token::Ident(name)) = &self.current {
                    let name = name.clone();
                    self.advance();
                    
                    if name == "T" {
                        // Transpose
                        expr = Expr::Transpose(Box::new(expr));
                    } else if self.check(&Token::LParen) {
                        // Method call
                        let args = self.parse_args()?;
                        expr = Expr::FunctionCall {
                            name: format!(".{}", name),
                            args: std::iter::once(expr).chain(args).collect(),
                        };
                    } else {
                        return Err(CompileError::parse_error(format!(
                            "Unknown attribute: {}",
                            name
                        )));
                    }
                } else {
                    return Err(CompileError::parse_error("Expected identifier after '.'"));
                }
            } else {
                break;
            }
        }
        
        Ok(expr)
    }
    
    /// Parse primary expressions: literals, variables, parenthesized, function calls
    fn parse_primary(&mut self) -> CompileResult<Expr> {
        match &self.current {
            Some(Token::Number(n)) => {
                let n = *n;
                self.advance();
                Ok(Expr::Scalar(n))
            }
            
            Some(Token::Ident(name)) => {
                let name = name.clone();
                self.advance();
                
                // Check for numpy function: np.func()
                if name == "np" && self.check(&Token::Dot) {
                    self.advance();
                    if let Some(Token::Ident(func_name)) = &self.current {
                        let func_name = func_name.clone();
                        self.advance();
                        let args = self.parse_args()?;
                        return Ok(Expr::FunctionCall {
                            name: format!("np.{}", func_name),
                            args,
                        });
                    } else {
                        return Err(CompileError::parse_error("Expected function name after 'np.'"));
                    }
                }
                
                // Check for function call: name()
                if self.check(&Token::LParen) {
                    let args = self.parse_args()?;
                    return Ok(Expr::FunctionCall { name, args });
                }
                
                Ok(Expr::Variable(name))
            }
            
            Some(Token::LParen) => {
                self.advance();
                
                // Check for tuple
                let first = self.parse_expr()?;
                
                if self.check(&Token::Comma) {
                    // It's a tuple
                    let mut elements = vec![first];
                    while self.check(&Token::Comma) {
                        self.advance();
                        if self.check(&Token::RParen) {
                            break; // Trailing comma
                        }
                        elements.push(self.parse_expr()?);
                    }
                    self.expect(Token::RParen)?;
                    Ok(Expr::Tuple(elements))
                } else {
                    // Just a parenthesized expression
                    self.expect(Token::RParen)?;
                    Ok(first)
                }
            }
            
            Some(Token::LBracket) => {
                self.parse_matrix_literal()
            }
            
            None => Err(CompileError::parse_error("Unexpected end of input")),
            
            other => Err(CompileError::parse_error(format!(
                "Unexpected token: {:?}",
                other
            ))),
        }
    }
    
    /// Parse function arguments: (arg1, arg2, ...)
    fn parse_args(&mut self) -> CompileResult<Vec<Expr>> {
        self.expect(Token::LParen)?;
        
        let mut args = Vec::new();
        
        if !self.check(&Token::RParen) {
            args.push(self.parse_expr()?);
            
            while self.check(&Token::Comma) {
                self.advance();
                if self.check(&Token::RParen) {
                    break; // Trailing comma
                }
                args.push(self.parse_expr()?);
            }
        }
        
        self.expect(Token::RParen)?;
        Ok(args)
    }
    
    /// Parse a matrix literal: [[1, 2], [3, 4]]
    fn parse_matrix_literal(&mut self) -> CompileResult<Expr> {
        self.expect(Token::LBracket)?;
        
        // Check if it's a 1D array or 2D matrix
        if self.check(&Token::LBracket) {
            // 2D matrix
            let mut rows = Vec::new();
            
            while !self.check(&Token::RBracket) {
                rows.push(self.parse_row()?);
                
                if self.check(&Token::Comma) {
                    self.advance();
                }
            }
            
            self.expect(Token::RBracket)?;
            
            // Validate all rows have same length
            if !rows.is_empty() {
                let expected_len = rows[0].len();
                for (i, row) in rows.iter().enumerate() {
                    if row.len() != expected_len {
                        return Err(CompileError::parse_error(format!(
                            "Row {} has {} elements, expected {}",
                            i, row.len(), expected_len
                        )));
                    }
                }
            }
            
            Ok(Expr::Matrix(MatrixLiteral::new(rows)))
        } else {
            // 1D array - treat as row vector
            let row = self.parse_number_list()?;
            self.expect(Token::RBracket)?;
            Ok(Expr::Matrix(MatrixLiteral::new(vec![row])))
        }
    }
    
    /// Parse a row: [1, 2, 3]
    fn parse_row(&mut self) -> CompileResult<Vec<f64>> {
        self.expect(Token::LBracket)?;
        let values = self.parse_number_list()?;
        self.expect(Token::RBracket)?;
        Ok(values)
    }
    
    /// Parse a list of numbers
    fn parse_number_list(&mut self) -> CompileResult<Vec<f64>> {
        let mut values = Vec::new();
        
        if !self.check(&Token::RBracket) {
            if let Some(Token::Number(n)) = &self.current {
                values.push(*n);
                self.advance();
            } else if self.check(&Token::Minus) {
                self.advance();
                if let Some(Token::Number(n)) = &self.current {
                    values.push(-*n);
                    self.advance();
                } else {
                    return Err(CompileError::parse_error("Expected number after '-'"));
                }
            } else {
                return Err(CompileError::parse_error("Expected number in matrix literal"));
            }
            
            while self.check(&Token::Comma) {
                self.advance();
                if self.check(&Token::RBracket) {
                    break; // Trailing comma
                }
                
                if let Some(Token::Number(n)) = &self.current {
                    values.push(*n);
                    self.advance();
                } else if self.check(&Token::Minus) {
                    self.advance();
                    if let Some(Token::Number(n)) = &self.current {
                        values.push(-*n);
                        self.advance();
                    } else {
                        return Err(CompileError::parse_error("Expected number after '-'"));
                    }
                } else {
                    return Err(CompileError::parse_error("Expected number in matrix literal"));
                }
            }
        }
        
        Ok(values)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_parse_matmul() {
        let mut parser = Parser::new("A @ B");
        let program = parser.parse_program().unwrap();
        
        assert_eq!(program.statements.len(), 1);
        if let Statement::Expression(Expr::MatMul(left, right)) = &program.statements[0] {
            assert!(matches!(left.as_ref(), Expr::Variable(name) if name == "A"));
            assert!(matches!(right.as_ref(), Expr::Variable(name) if name == "B"));
        } else {
            panic!("Expected MatMul expression");
        }
    }
    
    #[test]
    fn test_parse_assignment() {
        let mut parser = Parser::new("C = A @ B");
        let program = parser.parse_program().unwrap();
        
        assert_eq!(program.statements.len(), 1);
        if let Statement::Assignment { target, value } = &program.statements[0] {
            assert_eq!(target, "C");
            assert!(matches!(value, Expr::MatMul(_, _)));
        } else {
            panic!("Expected Assignment");
        }
    }
    
    #[test]
    fn test_parse_matrix_literal() {
        let mut parser = Parser::new("[[1, 2], [3, 4]]");
        let program = parser.parse_program().unwrap();
        
        assert_eq!(program.statements.len(), 1);
        if let Statement::Expression(Expr::Matrix(mat)) = &program.statements[0] {
            assert_eq!(mat.rows, vec![vec![1.0, 2.0], vec![3.0, 4.0]]);
        } else {
            panic!("Expected Matrix literal");
        }
    }
    
    #[test]
    fn test_parse_transpose() {
        let mut parser = Parser::new("A.T");
        let program = parser.parse_program().unwrap();
        
        assert_eq!(program.statements.len(), 1);
        if let Statement::Expression(Expr::Transpose(inner)) = &program.statements[0] {
            assert!(matches!(inner.as_ref(), Expr::Variable(name) if name == "A"));
        } else {
            panic!("Expected Transpose expression");
        }
    }
    
    #[test]
    fn test_parse_complex_expr() {
        let mut parser = Parser::new("C = A @ B + D @ E.T");
        let program = parser.parse_program().unwrap();
        
        assert_eq!(program.statements.len(), 1);
        if let Statement::Assignment { target, value } = &program.statements[0] {
            assert_eq!(target, "C");
            assert!(matches!(value, Expr::Add(_, _)));
        } else {
            panic!("Expected Assignment");
        }
    }
    
    #[test]
    fn test_parse_numpy_function() {
        let mut parser = Parser::new("A = np.zeros((3, 4))");
        let program = parser.parse_program().unwrap();
        
        assert_eq!(program.statements.len(), 1);
        if let Statement::Assignment { target, value } = &program.statements[0] {
            assert_eq!(target, "A");
            if let Expr::FunctionCall { name, args } = value {
                assert_eq!(name, "np.zeros");
                assert_eq!(args.len(), 1);
            } else {
                panic!("Expected FunctionCall");
            }
        } else {
            panic!("Expected Assignment");
        }
    }
}
