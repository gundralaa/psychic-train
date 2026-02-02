//! NumPy to Systolic Array Compiler CLI
//!
//! Usage:
//!   numpy2systolic "C = A @ B" --shape A=3x4 --shape B=4x5
//!   numpy2systolic -f program.numpy --array-size 4
//!   numpy2systolic "[[1,2],[3,4]] @ [[5,6],[7,8]]" --json

use clap::Parser as ClapParser;
use colored::Colorize;
use std::fs;
use std::io::{self, Read};

use numpy_to_systolic::{
    compile_with_shapes, Analyzer, CodeGenerator, HardwareProgram, Parser,
    SystolicConfig, TilingStrategy,
};

#[derive(ClapParser, Debug)]
#[command(name = "numpy2systolic")]
#[command(author = "FPGA Team")]
#[command(version = "0.1.0")]
#[command(about = "Compiles NumPy expressions to systolic array passes")]
struct Args {
    /// NumPy expression to compile (e.g., "C = A @ B")
    #[arg(value_name = "EXPR")]
    expression: Option<String>,

    /// Read expression from file
    #[arg(short = 'f', long = "file")]
    input_file: Option<String>,

    /// Define matrix shapes (e.g., "A=3x4")
    #[arg(short = 's', long = "shape", value_parser = parse_shape)]
    shapes: Vec<(String, (usize, usize))>,

    /// Systolic array size (NxN)
    #[arg(short = 'n', long = "array-size", default_value = "3")]
    array_size: usize,

    /// Data width in bits
    #[arg(short = 'd', long = "data-width", default_value = "8")]
    data_width: usize,

    /// Accumulator width in bits
    #[arg(short = 'a', long = "acc-width", default_value = "32")]
    acc_width: usize,

    /// Output as JSON
    #[arg(short = 'j', long = "json")]
    json_output: bool,

    /// Output Chisel test format
    #[arg(long = "chisel")]
    chisel_output: bool,

    /// Verbose output
    #[arg(short = 'v', long = "verbose")]
    verbose: bool,
}

fn parse_shape(s: &str) -> Result<(String, (usize, usize)), String> {
    let parts: Vec<&str> = s.split('=').collect();
    if parts.len() != 2 {
        return Err(format!("Invalid shape format: {}", s));
    }

    let name = parts[0].to_string();
    let dims: Vec<&str> = parts[1].split('x').collect();
    if dims.len() != 2 {
        return Err(format!("Invalid dimensions: {}", parts[1]));
    }

    let rows = dims[0]
        .parse::<usize>()
        .map_err(|_| format!("Invalid row count: {}", dims[0]))?;
    let cols = dims[1]
        .parse::<usize>()
        .map_err(|_| format!("Invalid col count: {}", dims[1]))?;

    Ok((name, (rows, cols)))
}

fn main() {
    let args = Args::parse();

    // Get expression from argument, file, or stdin
    let expression = if let Some(expr) = args.expression {
        expr
    } else if let Some(file) = args.input_file {
        fs::read_to_string(&file).unwrap_or_else(|e| {
            eprintln!("{}: Failed to read file '{}': {}", "Error".red(), file, e);
            std::process::exit(1);
        })
    } else {
        // Read from stdin
        let mut buffer = String::new();
        io::stdin().read_to_string(&mut buffer).unwrap_or_else(|e| {
            eprintln!("{}: Failed to read stdin: {}", "Error".red(), e);
            std::process::exit(1);
        });
        buffer
    };

    if args.verbose {
        println!("{}", "NumPy to Systolic Array Compiler".bold().blue());
        println!("{}", "=".repeat(35));
        println!();
        println!("{}: {}", "Input".green(), expression.trim());
        println!(
            "{}: {}x{} ({}-bit data, {}-bit accum)",
            "Target array".green(),
            args.array_size,
            args.array_size,
            args.data_width,
            args.acc_width
        );
        println!();
    }

    // Create configuration
    let config = SystolicConfig::new(args.array_size, args.data_width, args.acc_width);

    // Convert shapes to the required format
    let shapes: Vec<(&str, (usize, usize))> = args
        .shapes
        .iter()
        .map(|(name, shape)| (name.as_str(), *shape))
        .collect();

    // Compile
    let result = if shapes.is_empty() {
        // Try to compile without explicit shapes (using literal matrices)
        let mut parser = Parser::new(&expression);
        let program = match parser.parse_program() {
            Ok(p) => p,
            Err(e) => {
                eprintln!("{}: {}", "Parse error".red(), e);
                std::process::exit(1);
            }
        };

        let mut analyzer = Analyzer::new();
        let typed = match analyzer.analyze(program) {
            Ok(t) => t,
            Err(e) => {
                eprintln!("{}: {}", "Type error".red(), e);
                std::process::exit(1);
            }
        };

        let tiler = TilingStrategy::new(config.clone());
        let tiled = match tiler.tile_program(&typed) {
            Ok(t) => t,
            Err(e) => {
                eprintln!("{}: {}", "Tiling error".red(), e);
                std::process::exit(1);
            }
        };

        let mut codegen = CodeGenerator::new(config);
        match codegen.generate(tiled) {
            Ok(p) => p,
            Err(e) => {
                eprintln!("{}: {}", "Code generation error".red(), e);
                std::process::exit(1);
            }
        }
    } else {
        match compile_with_shapes(&expression, &shapes, &config) {
            Ok(p) => p,
            Err(e) => {
                eprintln!("{}: {}", "Compilation error".red(), e);
                std::process::exit(1);
            }
        }
    };

    // Output
    if args.json_output {
        match result.to_json() {
            Ok(json) => println!("{}", json),
            Err(e) => {
                eprintln!("{}: Failed to serialize to JSON: {}", "Error".red(), e);
                std::process::exit(1);
            }
        }
    } else if args.chisel_output {
        println!("{}", result.to_chisel_test_format());
    } else {
        print_program(&result, args.verbose);
    }
}

fn print_program(program: &HardwareProgram, verbose: bool) {
    println!("{}", "Compilation Results".bold().green());
    println!("{}", "=".repeat(50));
    println!();

    println!(
        "{}: {}x{} systolic array",
        "Target".cyan(),
        program.config.array_size,
        program.config.array_size
    );
    println!(
        "{}: {}-bit data, {}-bit accumulator",
        "Width".cyan(),
        program.config.data_width,
        program.config.acc_width
    );
    println!("{}: {}", "Output shape".cyan(), format!("{:?}", program.output_shape));
    println!("{}: {}", "Total passes".cyan(), program.passes.len());
    println!(
        "{}: {} cycles",
        "Total cycles".cyan(),
        program.total_cycles
    );
    println!();

    if verbose || program.passes.len() <= 8 {
        println!("{}", "Pass Details".bold().yellow());
        println!("{}", "-".repeat(50));

        for pass in &program.passes {
            println!();
            println!("{} {}", "Pass".bold(), pass.id.to_string().bold());
            println!("  {}: {}", "Description".cyan(), pass.description);
            println!("  {}: {:?}", "A shape".cyan(), pass.a_shape);
            println!("  {}: {:?}", "B shape".cyan(), pass.b_shape);
            println!(
                "  {}: ({}, {})",
                "Output tile".cyan(),
                pass.output_tile.tile_row,
                pass.output_tile.tile_col
            );
            println!("  {}: {:?}", "Operation".cyan(), pass.operation);

            if verbose {
                println!(
                    "  {}: {:?}",
                    "Matrix A (row-major)".cyan(),
                    pass.matrix_a
                );
                println!(
                    "  {}: {:?}",
                    "Matrix B (col-major)".cyan(),
                    pass.matrix_b
                );
            }
        }
    } else {
        println!(
            "({} passes, use -v for details)",
            program.passes.len()
        );
    }
}
