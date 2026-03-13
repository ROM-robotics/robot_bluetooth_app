# Bluetooth RFCOMM Server CLI

Python-based Bluetooth RFCOMM server that allows Android/mobile clients to connect and control WiFi settings on Ubuntu/Linux PC.

## Features

- **No Passkey Required** - Uses `NoInputNoOutput` capability for seamless pairing
- **Auto Re-pairing** - Automatically handles "forget device" scenarios
- **Real-time Connection Tracking** - Shows connect/disconnect events via D-Bus
- **WiFi Control** - Search, connect, and check WiFi status via Bluetooth

## Quick Start

```bash
# 1. Setup BlueZ for auto-repairing (one-time setup)
cd rom_bt_eth_server
sudo bash setup_bluez_repairing.sh

# 2. Run the server
sudo python3 bt_server_cli.py -c 1 -n "MyRobot"
```

## Requirements

- Ubuntu/Debian Linux with BlueZ 5.x
- Python 3.8+
- D-Bus and GLib Python bindings

```bash
# Install dependencies
sudo apt install python3-dbus python3-gi bluez
```

## Command Line Options

| Option | Default | Description |
|--------|---------|-------------|
| `-c, --channel` | 1 | RFCOMM channel number (1-30) |
| `-n, --name` | ROM_Robot | Bluetooth device name |
| `--clear-paired` | false | Remove all paired devices before starting |

## BlueZ Configuration (`/etc/bluetooth/main.conf`)

The `setup_bluez_repairing.sh` script adds these settings:

```ini
[General]
# Auto re-pair when client forgets device and reconnects
JustWorksRepairing = always

# Auto-enable Bluetooth on startup
AutoEnable = true

# Stay discoverable forever (0 = no timeout)
DiscoverableTimeout = 0

# Stay pairable forever
PairableTimeout = 0
```

### Why These Settings?

| Setting | Purpose |
|---------|---------|
| `JustWorksRepairing = always` | **Critical**: Fixes "incorrect passkey" error when client forgets device and reconnects. Without this, server keeps old pairing keys that don't match. |
| `AutoEnable = true` | Ensures Bluetooth is ready after system boot |
| `DiscoverableTimeout = 0` | Server stays visible to clients indefinitely |
| `PairableTimeout = 0` | Server can accept pairing requests anytime |

## Bluetooth Service Configuration

### Do I Need `--compat` Flag?

**Short Answer**: Usually **not required** for this server.

The `--compat` flag enables the legacy SDP (Service Discovery Protocol) server interface. Here's when you need it:

| Scenario | `--compat` Needed? |
|----------|-------------------|
| Using `sdptool` to register services | Yes |
| Using legacy BlueZ 4.x APIs | Yes |
| Using Python `socket.AF_BLUETOOTH` directly | **No** |
| Using Qt Bluetooth APIs | **No** |

**This server uses `socket.AF_BLUETOOTH` directly**, so `--compat` is optional. BlueZ 5.x handles SDP automatically for RFCOMM sockets.

### If You Want to Use `--compat` (Optional)

Edit the bluetooth service:

```bash
sudo nano /lib/systemd/system/bluetooth.service
```

Change:
```ini
ExecStart=/usr/lib/bluetooth/bluetoothd
```

To:
```ini
ExecStart=/usr/lib/bluetooth/bluetoothd --compat
```

Then reload:
```bash
sudo systemctl daemon-reload
sudo systemctl restart bluetooth
```

### Do I Need to Restart Bluetooth Before Running Server?

**Only if you changed `/etc/bluetooth/main.conf`**

```bash
# After running setup_bluez_repairing.sh or editing main.conf
sudo systemctl restart bluetooth

# Wait a moment for adapter to initialize
sleep 2

# Then run server
sudo python3 bt_server_cli.py -c 1 -n "MyRobot"
```

If you haven't changed any config, just run the server directly.

## Supported Commands

Commands sent from client to server:

| Command | Response | Description |
|---------|----------|-------------|
| `PING` | `PONG` | Connection test |
| `SEARCH_WIFI` | `WIFI_LIST:SSID1\|Signal1\|Security1,...` | List available WiFi networks |
| `CONNECT_WIFI:ssid:password` | `CONNECT_OK` or `CONNECT_FAIL:reason` | Connect to WiFi |
| `CURRENT_WIFI` | `CURRENT_WIFI:ssid` or `CURRENT_WIFI:NOT_CONNECTED` | Get current WiFi |

## Troubleshooting

### Problem: "Incorrect passkey" when reconnecting after forget device

**Solution**: Run the setup script to enable `JustWorksRepairing`:

```bash
sudo bash setup_bluez_repairing.sh
sudo systemctl restart bluetooth
```

### Problem: Device name shows hostname instead of custom name

**Solution**: 
1. Restart bluetooth service after changing name
2. On client, forget the device and scan again

```bash
sudo systemctl restart bluetooth
sudo python3 bt_server_cli.py -n "MyRobot"
```

### Problem: Permission denied errors

**Solution**: Run with sudo:

```bash
sudo python3 bt_server_cli.py
```

### Problem: "Could not set security level" warning

**Solution**: This is usually harmless. The server will still work. If connections fail, try adding `--compat`:

```bash
# Edit bluetooth service to add --compat
sudo sed -i 's|ExecStart=/usr/lib/bluetooth/bluetoothd|ExecStart=/usr/lib/bluetooth/bluetoothd --compat|' /lib/systemd/system/bluetooth.service
sudo systemctl daemon-reload
sudo systemctl restart bluetooth
```

### Problem: Client can't find the server

**Solution**: Ensure adapter is discoverable:

```bash
bluetoothctl
> power on
> discoverable on
> exit
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     bt_server_cli.py                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────┐    ┌──────────────────────────────┐   │
│  │ NoPinPairing    │    │ BluetoothServerCLI           │   │
│  │ Agent           │    │                              │   │
│  │                 │    │  • RFCOMM Socket (AF_BT)     │   │
│  │ • Auto-accept   │    │  • Command Handler           │   │
│  │   pairing       │    │  • WiFi Control (nmcli)      │   │
│  │ • NoInputNo     │    │  • Connection Tracking       │   │
│  │   Output mode   │    │                              │   │
│  └────────┬────────┘    └──────────────┬───────────────┘   │
│           │                            │                    │
│           ▼                            ▼                    │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              D-Bus (org.bluez)                      │   │
│  │  • AgentManager1 - Register pairing agent           │   │
│  │  • PropertiesChanged - Track connect/disconnect     │   │
│  └─────────────────────────────────────────────────────┘   │
│                            │                                │
└────────────────────────────┼────────────────────────────────┘
                             │
                             ▼
                    ┌────────────────┐
                    │   BlueZ 5.x    │
                    │   bluetoothd   │
                    └────────────────┘
                             │
                             ▼
                    ┌────────────────┐
                    │  HCI Adapter   │
                    │   (hci0)       │
                    └────────────────┘
                             │
                             ▼
                    ┌────────────────┐
                    │ Android Client │
                    │ (RFCOMM SPP)   │
                    └────────────────┘
```

## Files

| File | Description |
|------|-------------|
| `bt_server_cli.py` | Main server script |
| `setup_bluez_repairing.sh` | BlueZ configuration script |
| `bt_rfcomm_server_v2.py` | Alternative server implementation |
| `bt_rfcomm_server.py` | Legacy server (requires PyBluez) |

## License

MIT License
