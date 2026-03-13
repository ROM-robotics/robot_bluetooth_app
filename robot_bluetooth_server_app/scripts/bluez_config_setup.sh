#!/bin/bash
# Setup BlueZ for automatic re-pairing (no passkey issues after forget device)
# Run with: sudo bash setup_bluez_repairing.sh

set -e

CONF_FILE="/etc/bluetooth/main.conf"
BACKUP_FILE="/etc/bluetooth/main.conf.backup"

echo "=== BlueZ Auto-Repairing Setup ==="
echo ""

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "Please run with sudo:"
    echo "  sudo bash $0"
    exit 1
fi

# Backup original config
if [ ! -f "$BACKUP_FILE" ]; then
    echo "[1/4] Backing up original config..."
    cp "$CONF_FILE" "$BACKUP_FILE"
    echo "  ✓ Backup saved to $BACKUP_FILE"
else
    echo "[1/4] Backup already exists"
fi

# Check if settings already exist
echo "[2/4] Checking current settings..."

if grep -q "^JustWorksRepairing" "$CONF_FILE"; then
    echo "  JustWorksRepairing already configured"
else
    echo "  Adding JustWorksRepairing = always"
    # Add after [General] section
    sed -i '/^\[General\]/a JustWorksRepairing = always' "$CONF_FILE"
fi

if grep -q "^AutoEnable" "$CONF_FILE"; then
    echo "  AutoEnable already configured"
else
    echo "  Adding AutoEnable = true"
    sed -i '/^\[General\]/a AutoEnable = true' "$CONF_FILE"
fi

# Uncomment and set DiscoverableTimeout = 0
if grep -q "^DiscoverableTimeout = 0" "$CONF_FILE"; then
    echo "  DiscoverableTimeout already set to 0"
else
    echo "  Setting DiscoverableTimeout = 0"
    sed -i 's/^#DiscoverableTimeout = 0/DiscoverableTimeout = 0/' "$CONF_FILE"
    # If uncommented version doesn't exist, add it
    if ! grep -q "^DiscoverableTimeout = 0" "$CONF_FILE"; then
        sed -i '/^\[General\]/a DiscoverableTimeout = 0' "$CONF_FILE"
    fi
fi

# Uncomment and set PairableTimeout = 0
if grep -q "^PairableTimeout = 0" "$CONF_FILE"; then
    echo "  PairableTimeout already set to 0"
else
    echo "  Setting PairableTimeout = 0"
    sed -i 's/^#PairableTimeout = 0/PairableTimeout = 0/' "$CONF_FILE"
    if ! grep -q "^PairableTimeout = 0" "$CONF_FILE"; then
        sed -i '/^\[General\]/a PairableTimeout = 0' "$CONF_FILE"
    fi
fi

echo ""
echo "[3/4] Current [General] section:"
echo "----------------------------------------"
sed -n '/^\[General\]/,/^\[/p' "$CONF_FILE" | head -20
echo "----------------------------------------"

echo ""
echo "[4/4] Restarting Bluetooth service..."
systemctl restart bluetooth
sleep 2

if systemctl is-active --quiet bluetooth; then
    echo "  ✓ Bluetooth service restarted successfully"
else
    echo "  ⚠ Bluetooth service may have issues"
    systemctl status bluetooth --no-pager
fi

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Key settings enabled:"
echo "  • JustWorksRepairing = always"
echo "    → Automatically re-pair when client forgets device"
echo ""
echo "  • AutoEnable = true"
echo "    → Auto-enable Bluetooth on startup"
echo ""
echo "  • DiscoverableTimeout = 0"
echo "    → Stay discoverable forever"
echo ""
echo "  • PairableTimeout = 0"
echo "    → Stay pairable forever"
echo ""
echo "Now run your server:"
echo "  sudo python3 bt_server_cli.py -c 2 -n \"MyRobot\""
echo ""
