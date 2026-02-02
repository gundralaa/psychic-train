//! Hardware interface definitions for the systolic array
//!
//! These types correspond to the Chisel hardware implementation in
//! hardware_examples/src/main/scala/systolic/

use serde::{Deserialize, Serialize};

/// Configuration for the systolic array hardware
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SystolicConfig {
    /// Size of the NxN array (e.g., 3 for a 3x3 array)
    pub array_size: usize,
    /// Bit width of input matrix elements
    pub data_width: usize,
    /// Bit width of accumulator/result
    pub acc_width: usize,
}

impl SystolicConfig {
    pub fn new(array_size: usize, data_width: usize, acc_width: usize) -> Self {
        Self {
            array_size,
            data_width,
            acc_width,
        }
    }
    
    /// Default configuration matching the Chisel toy example
    pub fn default_3x3() -> Self {
        Self::new(3, 8, 32)
    }
    
    /// Get the maximum value that can be represented
    pub fn max_value(&self) -> i64 {
        (1i64 << (self.data_width - 1)) - 1
    }
    
    /// Get the minimum value that can be represented
    pub fn min_value(&self) -> i64 {
        -(1i64 << (self.data_width - 1))
    }
    
    /// Number of cycles needed for one matrix multiplication
    pub fn cycles_for_matmul(&self) -> usize {
        3 * self.array_size - 1
    }
}

impl Default for SystolicConfig {
    fn default() -> Self {
        Self::default_3x3()
    }
}

/// A single pass through the systolic array
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SystolicPass {
    /// Unique identifier for this pass
    pub id: usize,
    /// Human-readable description
    pub description: String,
    /// Matrix A data (row-major, flattened)
    pub matrix_a: Vec<i64>,
    /// Matrix A dimensions (rows, cols)
    pub a_shape: (usize, usize),
    /// Matrix B data (column-major, flattened)
    pub matrix_b: Vec<i64>,
    /// Matrix B dimensions (rows, cols)
    pub b_shape: (usize, usize),
    /// Expected output dimensions
    pub output_shape: (usize, usize),
    /// Which tile of the output this contributes to
    pub output_tile: TileCoord,
    /// Operation type
    pub operation: PassOperation,
}

/// Coordinate of a tile in a larger matrix
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub struct TileCoord {
    /// Row tile index
    pub tile_row: usize,
    /// Column tile index
    pub tile_col: usize,
    /// Starting row in the full matrix
    pub start_row: usize,
    /// Starting column in the full matrix
    pub start_col: usize,
}

impl TileCoord {
    pub fn single() -> Self {
        Self {
            tile_row: 0,
            tile_col: 0,
            start_row: 0,
            start_col: 0,
        }
    }
    
    pub fn new(tile_row: usize, tile_col: usize, start_row: usize, start_col: usize) -> Self {
        Self {
            tile_row,
            tile_col,
            start_row,
            start_col,
        }
    }
}

/// Type of operation for a pass
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum PassOperation {
    /// Initialize result tile (clear accumulators)
    Initialize,
    /// Accumulate partial product
    Accumulate,
    /// Final result (no more accumulation needed)
    Final,
}

/// A complete hardware program
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HardwareProgram {
    /// Configuration for the target systolic array
    pub config: SystolicConfig,
    /// Sequence of passes to execute
    pub passes: Vec<SystolicPass>,
    /// Final output shape
    pub output_shape: (usize, usize),
    /// Total estimated cycles
    pub total_cycles: usize,
    /// Human-readable summary
    pub summary: String,
}

impl HardwareProgram {
    pub fn new(config: SystolicConfig) -> Self {
        Self {
            config,
            passes: Vec::new(),
            output_shape: (0, 0),
            total_cycles: 0,
            summary: String::new(),
        }
    }
    
    /// Add a pass to the program
    pub fn add_pass(&mut self, pass: SystolicPass) {
        self.total_cycles += self.config.cycles_for_matmul();
        self.passes.push(pass);
    }
    
    /// Generate a summary of the program
    pub fn generate_summary(&mut self) {
        let num_passes = self.passes.len();
        let cycles_per_pass = self.config.cycles_for_matmul();
        
        self.summary = format!(
            "Hardware Program Summary:\n\
             =========================\n\
             Target: {}x{} systolic array ({}-bit data, {}-bit accumulator)\n\
             Passes: {}\n\
             Cycles per pass: {}\n\
             Total cycles: {}\n\
             Output shape: {:?}\n",
            self.config.array_size,
            self.config.array_size,
            self.config.data_width,
            self.config.acc_width,
            num_passes,
            cycles_per_pass,
            self.total_cycles,
            self.output_shape
        );
    }
    
    /// Export to JSON format
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string_pretty(self)
    }
    
    /// Export to a format suitable for the Chisel testbench
    pub fn to_chisel_test_format(&self) -> String {
        let mut output = String::new();
        
        output.push_str("// Auto-generated test vectors for SystolicArrayTop\n");
        output.push_str(&format!("// Array size: {}x{}\n\n", 
            self.config.array_size, self.config.array_size));
        
        for (i, pass) in self.passes.iter().enumerate() {
            output.push_str(&format!("// Pass {}: {}\n", i, pass.description));
            
            // Matrix A (row-major)
            output.push_str(&format!("val matrixA_{} = VecInit(Seq(\n", i));
            for (j, val) in pass.matrix_a.iter().enumerate() {
                if j > 0 {
                    output.push_str(", ");
                }
                if j % self.config.array_size == 0 && j > 0 {
                    output.push_str("\n  ");
                }
                output.push_str(&format!("{}.S", val));
            }
            output.push_str("\n))\n\n");
            
            // Matrix B (column-major)
            output.push_str(&format!("val matrixB_{} = VecInit(Seq(\n", i));
            for (j, val) in pass.matrix_b.iter().enumerate() {
                if j > 0 {
                    output.push_str(", ");
                }
                if j % self.config.array_size == 0 && j > 0 {
                    output.push_str("\n  ");
                }
                output.push_str(&format!("{}.S", val));
            }
            output.push_str("\n))\n\n");
        }
        
        output
    }
}

impl std::fmt::Display for HardwareProgram {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        writeln!(f, "Hardware Program")?;
        writeln!(f, "================")?;
        writeln!(f, "Target: {}x{} systolic array", self.config.array_size, self.config.array_size)?;
        writeln!(f, "Data width: {}-bit, Accumulator: {}-bit", self.config.data_width, self.config.acc_width)?;
        writeln!(f, "Total passes: {}", self.passes.len())?;
        writeln!(f, "Total cycles: {}", self.total_cycles)?;
        writeln!(f, "Output shape: {:?}", self.output_shape)?;
        writeln!(f)?;
        
        for (i, pass) in self.passes.iter().enumerate() {
            writeln!(f, "Pass {}:", i)?;
            writeln!(f, "  Description: {}", pass.description)?;
            writeln!(f, "  A shape: {:?}", pass.a_shape)?;
            writeln!(f, "  B shape: {:?}", pass.b_shape)?;
            writeln!(f, "  Output tile: ({}, {})", pass.output_tile.tile_row, pass.output_tile.tile_col)?;
            writeln!(f, "  Operation: {:?}", pass.operation)?;
            writeln!(f, "  Matrix A (row-major): {:?}", pass.matrix_a)?;
            writeln!(f, "  Matrix B (col-major): {:?}", pass.matrix_b)?;
            writeln!(f)?;
        }
        
        Ok(())
    }
}

/// Convert floating point matrix to integer values for hardware
pub fn quantize_matrix(matrix: &[f64], scale: f64, config: &SystolicConfig) -> Vec<i64> {
    let max_val = config.max_value();
    let min_val = config.min_value();
    
    matrix.iter()
        .map(|&v| {
            let scaled = (v * scale).round() as i64;
            scaled.clamp(min_val, max_val)
        })
        .collect()
}

/// Convert row-major matrix to column-major format
pub fn row_to_column_major(matrix: &[i64], rows: usize, cols: usize) -> Vec<i64> {
    let mut result = vec![0i64; rows * cols];
    for i in 0..rows {
        for j in 0..cols {
            result[j * rows + i] = matrix[i * cols + j];
        }
    }
    result
}

/// Pad a matrix to fit the systolic array size
pub fn pad_matrix(matrix: &[i64], rows: usize, cols: usize, target_rows: usize, target_cols: usize) -> Vec<i64> {
    let mut result = vec![0i64; target_rows * target_cols];
    for i in 0..rows.min(target_rows) {
        for j in 0..cols.min(target_cols) {
            result[i * target_cols + j] = matrix[i * cols + j];
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_config() {
        let config = SystolicConfig::new(3, 8, 32);
        assert_eq!(config.max_value(), 127);
        assert_eq!(config.min_value(), -128);
        assert_eq!(config.cycles_for_matmul(), 8); // 3*3 - 1 = 8
    }
    
    #[test]
    fn test_row_to_column_major() {
        // 2x3 matrix: [[1,2,3], [4,5,6]]
        let row_major = vec![1, 2, 3, 4, 5, 6];
        let col_major = row_to_column_major(&row_major, 2, 3);
        // Column major: [1,4, 2,5, 3,6]
        assert_eq!(col_major, vec![1, 4, 2, 5, 3, 6]);
    }
    
    #[test]
    fn test_pad_matrix() {
        let matrix = vec![1, 2, 3, 4]; // 2x2
        let padded = pad_matrix(&matrix, 2, 2, 3, 3);
        assert_eq!(padded, vec![1, 2, 0, 3, 4, 0, 0, 0, 0]);
    }
}
