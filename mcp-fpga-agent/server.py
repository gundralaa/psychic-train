from mcp.server.fastmcp import FastMCP
import asyncio
import os
import subprocess
from typing import List, Optional
import tempfile
import pathlib

# Initialize the MCP server
mcp = FastMCP("fpga-agent-server")

# Mock database/state for the architecture demo
current_state = {
    "last_bitstream": None,
    "last_synthesis_log": "",
    "simulation_results": {},
    "timing_score": None,
    "power_estimate": None
}

# Configuration defaults
DEFAULT_PART = "xc7a100tcsg324-1" # Arty A7-100T
DEFAULT_XDC = "constraints/arty_a7_100t.xdc"

def generate_synthesis_tcl(top_module: str, hdl_files: List[str], part: str, xdc_file: str, output_dir: str) -> str:
    """Generates the TCL script for synthesis and implementation."""
    tcl_content = f"""
# Vivado Synthesis and Implementation Script
set output_dir {output_dir}
file mkdir $output_dir

# Set Part
set_part {part}

# Add Source Files
"""
    for f in hdl_files:
        tcl_content += f"read_verilog {f}\n"

    tcl_content += f"""
# Add Constraints
read_xdc {xdc_file}

# Synthesis
synth_design -top {top_module} -part {part}
write_checkpoint -force $output_dir/post_synth.dcp
report_timing_summary -file $output_dir/post_synth_timing_summary.rpt
report_utilization -file $output_dir/post_synth_util.rpt

# Optimization
opt_design

# Place
place_design
write_checkpoint -force $output_dir/post_place.dcp

# Route
route_design
write_checkpoint -force $output_dir/post_route.dcp
report_timing_summary -file $output_dir/post_route_timing_summary.rpt
report_timing -sort_by group -max_paths 5 -path_type summary -file $output_dir/post_route_timing.rpt
report_clock_utilization -file $output_dir/clock_util.rpt
report_utilization -file $output_dir/post_route_util.rpt
report_power -file $output_dir/post_route_power.rpt

# Bitstream
write_bitstream -force $output_dir/{top_module}.bit
"""
    return tcl_content

def generate_flash_tcl(bitstream_path: str, device_id: Optional[str]) -> str:
    """Generates the TCL script to program the FPGA."""
    # Assuming local hardware server
    tcl_content = f"""
open_hw_manager
connect_hw_server -url localhost:3121
current_hw_target [get_hw_targets */xilinx_tcf/Digilent/*]
set_property PROGRAM.FILE {{{bitstream_path}}} [current_hw_device]
program_hw_devices [current_hw_device]
refresh_hw_device [lindex [get_hw_devices] 0]
close_hw_manager
"""
    return tcl_content

@mcp.tool()
async def synthesize_design(hdl_files: List[str], top_module: str, part_number: str = DEFAULT_PART) -> str:
    """
    Synthesizes the FPGA design from HDL files using Vivado.
    Returns the synthesis log and path to generated bitstream.
    """
    build_dir = os.path.abspath("build")
    os.makedirs(build_dir, exist_ok=True)
    
    # Resolve XDC path
    xdc_path = os.path.abspath(DEFAULT_XDC)
    if not os.path.exists(xdc_path):
        return f"Error: Default constraint file not found at {xdc_path}"

    # Generate TCL script
    tcl_script = generate_synthesis_tcl(top_module, hdl_files, part_number, xdc_path, build_dir)
    tcl_file_path = os.path.join(build_dir, "run_synth.tcl")
    
    with open(tcl_file_path, "w") as f:
        f.write(tcl_script)
        
    print(f"Generated TCL script at {tcl_file_path}")
    
    # In a real environment, we would run:
    # process = await asyncio.create_subprocess_exec(
    #     "vivado", "-mode", "batch", "-source", tcl_file_path,
    #     stdout=subprocess.PIPE, stderr=subprocess.PIPE
    # )
    # stdout, stderr = await process.communicate()
    
    # MOCK EXECUTION FOR DEMO
    bitstream_path = os.path.join(build_dir, f"{top_module}.bit")
    log_path = os.path.join(build_dir, "vivado.log")
    
    # Simulate creating the bitstream and logs
    with open(bitstream_path, "w") as f:
        f.write("mock bitstream content")
    with open(log_path, "w") as f:
        f.write("Vivado Synthesis Completed Successfully\n0 Errors, 0 Warnings")
        
    current_state["last_synthesis_log"] = f"Ran script {tcl_file_path}. Log mocked."
    current_state["last_bitstream"] = bitstream_path
    
    return f"Synthesis process initiated using script: {tcl_file_path}\n(Mock) Bitstream generated at {bitstream_path}"

@mcp.tool()
async def flash_fpga(bitstream_path: str, device_id: Optional[str] = None) -> str:
    """
    Flashes the bitstream to the connected FPGA device using Vivado Hardware Manager.
    """
    if not os.path.exists(bitstream_path):
         return f"Error: Bitstream {bitstream_path} not found."
         
    tcl_script = generate_flash_tcl(bitstream_path, device_id)
    tcl_path = os.path.join(os.path.dirname(bitstream_path), "program_fpga.tcl")
    
    with open(tcl_path, "w") as f:
        f.write(tcl_script)
        
    # In real env: vivado -mode batch -source tcl_path
    return f"Generated programming script at {tcl_path}. (Mock) Device flashed successfully."

@mcp.tool()
async def run_verification(testbench_file: str, simulation_type: str = "behavioral") -> str:
    """
    Runs a testbench verification simulation using xvlog/xelab/xsim.
    """
    # Just mocking the command generation
    cmd = f"xvlog {testbench_file} && xelab -debug typical {testbench_file} -s top_sim && xsim top_sim -R"
    
    # Mock result
    result = f"Command: {cmd}\nTestbench passed. (Mocked)"
    current_state["simulation_results"][testbench_file] = result
    return result

@mcp.tool()
async def analyze_design(metric: str) -> str:
    """
    Analyzes the design for specific metrics based on generated reports.
    metric: 'timing', 'power', 'utilization'
    """
    build_dir = os.path.abspath("build")
    
    if metric == "timing":
        report_path = os.path.join(build_dir, "post_route_timing_summary.rpt")
        # In real env: read file content
        return f"Reading {report_path}...\n(Mock) WNS: 0.5ns (Met). TNS: 0.0ns"
        
    elif metric == "power":
        report_path = os.path.join(build_dir, "post_route_power.rpt")
        return f"Reading {report_path}...\n(Mock) Total Power: 1.2W"
        
    elif metric == "utilization":
        report_path = os.path.join(build_dir, "post_route_util.rpt")
        return f"Reading {report_path}...\n(Mock) LUT: 45%, FF: 30%, BRAM: 10%"
        
    else:
        return f"Unknown metric: {metric}"

@mcp.resource("fpga://logs/synthesis")
def get_synthesis_log() -> str:
    """Get the last synthesis log"""
    return current_state["last_synthesis_log"]

@mcp.resource("fpga://reports/status")
def get_status_report() -> str:
    """Get a summary of the current design status"""
    return str(current_state)

if __name__ == "__main__":
    mcp.run()
