#!/bin/bash
# Install Bluetooth dependencies WITHOUT PyBluez
# This script sets up Bluetooth for the ROM Robot server

echo "=== ROM Robot Bluetooth Setup ==="
echo "Installing Bluetooth tools (WITHOUT PyBluez)..."
echo

# Update package list
echo "1. Updating package list..."
sudo apt-get update

# Install Bluetooth packages
echo "2. Installing Bluetooth tools..."
sudo apt-get install -y \
    bluetooth \
    bluez \
    bluez-tools \
    rfkill \
    network-manager \
    python3 \
    python3-pip \
    python3-dbus \
    python3-gi

# Install Python packages (optional, not required for basic operation)
echo "3. Installing Python networking tools..."
sudo pip3 install --upgrade setuptools wheel 2>/dev/null || true

# Enable Bluetooth service
echo "4. Enabling Bluetooth service..."
sudo systemctl enable bluetooth
sudo systemctl start bluetooth

# Unblock Bluetooth
echo "5. Unblocking Bluetooth..."
sudo rfkill unblock bluetooth

# Check Bluetooth status
echo
echo "=== Bluetooth Status ==="
hciconfig -a || echo "Warning: Could not get Bluetooth adapter info"

echo
echo "=== Installation Complete ==="
echo
echo "The server now uses Python's built-in socket interface (AF_BLUETOOTH)"
echo "No PyBluez dependency needed!"
echo
echo "To start the server:"
echo "  cd /home/mr_robot/Desktop/Git/BluetoothEthernetSystem/rom_bt_eth_server"
echo "  sudo python3 bt_rfcomm_server_v2.py"
echo
echo "Or use the updated start script:"
echo "  ./start_server_v2.sh"
