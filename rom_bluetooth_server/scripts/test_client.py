#!/usr/bin/env python3
"""
Simple Bluetooth RFCOMM Client for testing
"""

import socket
import sys

def test_connection(server_address, port=1):
    """Test connection to Bluetooth server"""
    try:
        print(f"Connecting to {server_address} on channel {port}...")
        
        # Create Bluetooth socket
        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        
        # Connect to server
        sock.connect((server_address, port))
        print("Connected successfully!")
        print()
        
        # Test commands
        commands = [
            ("PING", "Test connection"),
            ("CURRENT_WIFI", "Check current WiFi"),
            ("SEARCH_WIFI", "Scan WiFi networks"),
        ]
        
        for cmd, description in commands:
            print(f"Testing: {description}")
            print(f"Sending: {cmd}")
            
            # Send command
            sock.send(cmd.encode('utf-8'))
            
            # Receive response
            response = sock.recv(4096).decode('utf-8')
            print(f"Response: {response}")
            print()
        
        # Interactive mode
        print("=== Interactive Mode ===")
        print("Available commands:")
        print("  PING")
        print("  SEARCH_WIFI")
        print("  CURRENT_WIFI")
        print("  CONNECT_WIFI:SSID:PASSWORD")
        print("  quit - Exit")
        print()
        
        while True:
            cmd = input("Enter command: ").strip()
            
            if cmd.lower() == 'quit':
                break
            
            if not cmd:
                continue
            
            # Send command
            sock.send(cmd.encode('utf-8'))
            
            # Receive response
            response = sock.recv(4096).decode('utf-8')
            print(f"Response: {response}")
            print()
        
        sock.close()
        print("Disconnected")
        
    except Exception as e:
        print(f"Error: {e}")
        return False
    
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 test_client.py <BLUETOOTH_MAC_ADDRESS> [CHANNEL]")
        print("Example: python3 test_client.py 88:D8:2E:76:DD:5A")
        print("         python3 test_client.py 88:D8:2E:76:DD:5A 2")
        sys.exit(1)
    
    server_address = sys.argv[1]
    channel = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    test_connection(server_address, channel)
