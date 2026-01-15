from mcp.server.fastmcp import FastMCP
from saleae import automation
import os
import time
import json
from typing import Optional, List, Dict, Any

# Initialize the MCP server
mcp = FastMCP("saleae-probe")

# Global state to simulate hardware if real hardware isn't present
# In a real deployment, we might want to fail fast, but for development/demo
# inside a container without USB, we can fall back to mock behavior.
MOCK_MODE = os.environ.get("SALEAE_MOCK_MODE", "true").lower() == "true"

@mcp.tool()
async def check_connection() -> str:
    """
    Checks the connection to the Saleae Logic 2 software.
    Returns the version of the connected application or an error message.
    """
    if MOCK_MODE:
        return "Connected to Saleae Logic 2 (Mock Mode). Version: 2.4.0 (Mock)"

    try:
        # Connect to the local running Logic 2 instance
        manager = automation.Manager.connect(port=10430)
        info = manager.get_app_info()
        manager.close()
        return f"Connected to Saleae Logic 2. Version: {info.app_version}"
    except Exception as e:
        return f"Failed to connect to Saleae Logic 2: {str(e)}"

@mcp.tool()
async def capture_spi(
    duration_seconds: float = 1.0,
    miso_channel: int = 0,
    mosi_channel: int = 1,
    clk_channel: int = 2,
    enable_channel: int = 3,
    sample_rate: int = 10_000_000
) -> str:
    """
    Captures data from the Saleae probe and analyzes it as SPI.
    
    Args:
        duration_seconds: Length of capture in seconds.
        miso_channel: Digital channel for MISO (default 0).
        mosi_channel: Digital channel for MOSI (default 1).
        clk_channel: Digital channel for Clock (default 2).
        enable_channel: Digital channel for Enable (default 3).
        sample_rate: Digital sample rate in Hz (default 10MS/s).
        
    Returns:
        A summary of the captured SPI transactions.
    """
    if MOCK_MODE:
        # Simulate a delay for the capture
        time.sleep(min(duration_seconds, 2.0))
        return json.dumps({
            "status": "success",
            "message": "Capture complete (Mock)",
            "transaction_count": 5,
            "data_preview": ["0xAA", "0x55", "0x01", "0x02", "0xFF"],
            "file_path": "/tmp/mock_spi_capture.csv"
        }, indent=2)

    try:
        with automation.Manager.connect(port=10430) as manager:
            # Configure device
            device_config = automation.LogicDeviceConfiguration(
                enabled_digital_channels=[miso_channel, mosi_channel, clk_channel, enable_channel],
                digital_sample_rate=sample_rate,
                digital_threshold_volts=3.3, # Assuming 3.3V logic
            )

            capture_config = automation.CaptureConfiguration(
                capture_mode=automation.CaptureMode.TIMED,
                capture_duration_seconds=duration_seconds,
            )

            # Start capture
            with manager.start_capture(
                device_configuration=device_config,
                capture_configuration=capture_config
            ) as capture:
                # Wait for capture to complete
                capture.wait()

                # Add SPI Analyzer
                spi_settings = {
                    'MISO': miso_channel,
                    'MOSI': mosi_channel,
                    'Clock': clk_channel,
                    'Enable': enable_channel,
                    'Enable State': 'Enable Line is Active Low',
                    'Clock State': 'Clock is High when inactive (CPOL=1)',
                    'Clock Phase': 'Data is Valid on Clock Leading Edge (CPHA=0)',
                    'Bits per Transfer': '8 Bits per Transfer',
                    'Significant Bit': 'Most Significant Bit First'
                }
                
                spi_analyzer = capture.add_analyzer('SPI', label='SPI Analysis', settings=spi_settings)

                # Export Data
                export_file = os.path.abspath("spi_capture.csv")
                capture.export_data_table(
                    filepath=export_file,
                    analyzers=[spi_analyzer]
                )
                
                # Basic parsing of the exported file for summary (simplified)
                # In a real scenario, we might parse the CSV to return JSON data
                
                return f"Capture successful. Data exported to {export_file}. Analyzer added with settings: {spi_settings}"

    except Exception as e:
        return f"Error during capture: {str(e)}"

if __name__ == "__main__":
    mcp.run()
