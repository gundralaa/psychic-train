//! Lexer for NumPy-style expressions using logos
//!
//! Supports tokens like:
//! - Identifiers: A, B, matrix_name
//! - Numbers: 1, 2.5, -3.14
//! - Operators: @, +, -, *, /
//! - Punctuation: (, ), [, ], ,, =
//! - Keywords: np (for numpy functions)

use logos::Logos;

/// Token types for the NumPy expression language
#[derive(Logos, Debug, Clone, PartialEq)]
#[logos(skip r"[ \t\n\r]+")]  // Skip whitespace
pub enum Token {
    // Literals
    #[regex(r"-?[0-9]+\.?[0-9]*([eE][+-]?[0-9]+)?", |lex| lex.slice().parse::<f64>().ok())]
    Number(f64),
    
    // Identifiers and keywords
    #[regex(r"[a-zA-Z_][a-zA-Z0-9_]*", |lex| lex.slice().to_string())]
    Ident(String),
    
    // Operators
    #[token("@")]
    MatMul,
    
    #[token("+")]
    Plus,
    
    #[token("-")]
    Minus,
    
    #[token("*")]
    Star,
    
    #[token("/")]
    Slash,
    
    #[token("=")]
    Equals,
    
    #[token(".")]
    Dot,
    
    // Punctuation
    #[token("(")]
    LParen,
    
    #[token(")")]
    RParen,
    
    #[token("[")]
    LBracket,
    
    #[token("]")]
    RBracket,
    
    #[token(",")]
    Comma,
    
    #[token(";")]
    Semicolon,
    
    #[token(":")]
    Colon,
}

impl std::fmt::Display for Token {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Token::Number(n) => write!(f, "{}", n),
            Token::Ident(s) => write!(f, "{}", s),
            Token::MatMul => write!(f, "@"),
            Token::Plus => write!(f, "+"),
            Token::Minus => write!(f, "-"),
            Token::Star => write!(f, "*"),
            Token::Slash => write!(f, "/"),
            Token::Equals => write!(f, "="),
            Token::Dot => write!(f, "."),
            Token::LParen => write!(f, "("),
            Token::RParen => write!(f, ")"),
            Token::LBracket => write!(f, "["),
            Token::RBracket => write!(f, "]"),
            Token::Comma => write!(f, ","),
            Token::Semicolon => write!(f, ";"),
            Token::Colon => write!(f, ":"),
        }
    }
}

/// Lexer wrapper that provides a stream of tokens
pub struct Lexer<'source> {
    inner: logos::Lexer<'source, Token>,
    peeked: Option<Option<Result<Token, ()>>>,
}

impl<'source> Lexer<'source> {
    pub fn new(source: &'source str) -> Self {
        Self {
            inner: Token::lexer(source),
            peeked: None,
        }
    }
    
    /// Get current position in source
    pub fn span(&self) -> std::ops::Range<usize> {
        self.inner.span()
    }
    
    /// Peek at the next token without consuming it
    pub fn peek(&mut self) -> Option<&Result<Token, ()>> {
        if self.peeked.is_none() {
            self.peeked = Some(self.inner.next());
        }
        self.peeked.as_ref().unwrap().as_ref()
    }
    
    /// Check if the next token matches expected
    pub fn check(&mut self, expected: &Token) -> bool {
        match self.peek() {
            Some(Ok(tok)) => tok == expected,
            _ => false,
        }
    }
    
    /// Check if the next token is an identifier
    pub fn check_ident(&mut self) -> bool {
        matches!(self.peek(), Some(Ok(Token::Ident(_))))
    }
    
    /// Check if the next token is a number
    pub fn check_number(&mut self) -> bool {
        matches!(self.peek(), Some(Ok(Token::Number(_))))
    }
}

impl<'source> Iterator for Lexer<'source> {
    type Item = Result<Token, ()>;
    
    fn next(&mut self) -> Option<Self::Item> {
        if let Some(peeked) = self.peeked.take() {
            peeked
        } else {
            self.inner.next()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_simple_tokens() {
        let source = "A @ B";
        let tokens: Vec<_> = Lexer::new(source).filter_map(Result::ok).collect();
        assert_eq!(tokens, vec![
            Token::Ident("A".to_string()),
            Token::MatMul,
            Token::Ident("B".to_string()),
        ]);
    }
    
    #[test]
    fn test_assignment() {
        let source = "C = A @ B + D";
        let tokens: Vec<_> = Lexer::new(source).filter_map(Result::ok).collect();
        assert_eq!(tokens, vec![
            Token::Ident("C".to_string()),
            Token::Equals,
            Token::Ident("A".to_string()),
            Token::MatMul,
            Token::Ident("B".to_string()),
            Token::Plus,
            Token::Ident("D".to_string()),
        ]);
    }
    
    #[test]
    fn test_matrix_literal() {
        let source = "[[1, 2], [3, 4]]";
        let tokens: Vec<_> = Lexer::new(source).filter_map(Result::ok).collect();
        assert_eq!(tokens, vec![
            Token::LBracket,
            Token::LBracket,
            Token::Number(1.0),
            Token::Comma,
            Token::Number(2.0),
            Token::RBracket,
            Token::Comma,
            Token::LBracket,
            Token::Number(3.0),
            Token::Comma,
            Token::Number(4.0),
            Token::RBracket,
            Token::RBracket,
        ]);
    }
    
    #[test]
    fn test_numpy_function() {
        let source = "np.zeros((3, 4))";
        let tokens: Vec<_> = Lexer::new(source).filter_map(Result::ok).collect();
        assert_eq!(tokens, vec![
            Token::Ident("np".to_string()),
            Token::Dot,
            Token::Ident("zeros".to_string()),
            Token::LParen,
            Token::LParen,
            Token::Number(3.0),
            Token::Comma,
            Token::Number(4.0),
            Token::RParen,
            Token::RParen,
        ]);
    }
    
    #[test]
    fn test_transpose() {
        let source = "A.T";
        let tokens: Vec<_> = Lexer::new(source).filter_map(Result::ok).collect();
        assert_eq!(tokens, vec![
            Token::Ident("A".to_string()),
            Token::Dot,
            Token::Ident("T".to_string()),
        ]);
    }
}
