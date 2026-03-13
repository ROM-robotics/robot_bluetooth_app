#!/usr/bin/env python3
"""
Bluetooth Client Helper using BlueZ
Avoids Qt Bluetooth DBus issues
"""

import subprocess
import sys

def scan_devices():
    """Scan for Bluetooth devices"""
    try:
        result = subprocess.run(['bluetoothctl', 'scan', 'on'], 
                                timeout=10, capture_output=True, text=True)
        # Parse devices...
        return []
    except Exception as e:
        print(f"Error scanning: {e}", file=sys.stderr)
        return []

def pair_device(mac_address):
    """Pair with device"""
    try:
        subprocess.run(['bluetoothctl', 'pair', mac_address], check=True)
        subprocess.run(['bluetoothctl', 'trust', mac_address], check=True)
        return True
    except:
        return False

def connect_rfcomm(mac_address, channel=1):
    """Connect RFCOMM socket"""
    try:
        subprocess.run(['rfcomm', 'connect', '0', mac_address, str(channel)], check=True)
        return True
    except:
        return False

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python3 bt_client_helper.py <command> [args]")
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == 'scan':
        devices = scan_devices()
        print(devices)
    elif command == 'pair' and len(sys.argv) >= 3:
        success = pair_device(sys.argv[2])
        print('OK' if success else 'FAIL')
    elif command == 'connect' and len(sys.argv) >= 3:
        success = connect_rfcomm(sys.argv[2])
        print('OK' if success else 'FAIL')
