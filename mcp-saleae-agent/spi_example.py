import time
import os
import sys

# Attempt to import saleae.automation
try:
    from saleae import automation
except ImportError:
    print("saleae-logic-2-automation not installed. Please install it to run this example.")
    sys.exit(1)

def main():
    print("Starting SPI Capture Example...")
    
    # Check if we are running in a mock environment (no real hardware)
    # Since we can't easily detect hardware without connecting, we'll try to connect.
    try:
        manager = automation.Manager.connect(port=10430)
    except Exception as e:
        print(f"Could not connect to Logic 2 application: {e}")
        print("Ensure the Logic 2 app is running and the automation server is enabled (port 10430).")
        return

    try:
        print("Connected to Logic 2.")
        
        # Configuration
        # Assume pins: MISO=0, MOSI=1, CLK=2, CS=3
        miso_channel = 0
        mosi_channel = 1
        clk_channel = 2
        cs_channel = 3
        
        device_configuration = automation.LogicDeviceConfiguration(
            enabled_digital_channels=[miso_channel, mosi_channel, clk_channel, cs_channel],
            digital_sample_rate=10_000_000, # 10 MS/s
            digital_threshold_volts=3.3,
        )

        capture_configuration = automation.CaptureConfiguration(
            capture_mode=automation.CaptureMode.TIMED,
            capture_duration_seconds=5.0,
        )

        print("Starting 5 second capture...")
        with manager.start_capture(
            device_configuration=device_configuration,
            capture_configuration=capture_configuration
        ) as capture:
            
            # Wait until capture is finished
            capture.wait()
            print("Capture complete.")

            # Add SPI Analyzer
            print("Adding SPI Analyzer...")
            spi_analyzer = capture.add_analyzer('SPI', label='SPI Bus', settings={
                'MISO': miso_channel,
                'MOSI': mosi_channel,
                'Clock': clk_channel,
                'Enable': cs_channel,
                'Enable State': 'Enable Line is Active Low',
                'Clock State': 'Clock is High when inactive (CPOL=1)',
                'Clock Phase': 'Data is Valid on Clock Leading Edge (CPHA=0)',
                'Bits per Transfer': '8 Bits per Transfer',
                'Significant Bit': 'Most Significant Bit First'
            })

            # Export data to a CSV file
            export_path = os.path.abspath("spi_data.csv")
            print(f"Exporting data to {export_path}...")
            capture.export_data_table(
                filepath=export_path,
                analyzers=[spi_analyzer]
            )
            
            print("Done!")

    except Exception as e:
        print(f"An error occurred: {e}")
    finally:
        manager.close()

if __name__ == "__main__":
    main()
