# ROM Bluetooth Server

Robot PC (Ubuntu) တွင် run မည့် Python Bluetooth RFCOMM server။  
Android app မှ Bluetooth ဖြင့် ချိတ်ဆက်ပြီး WiFi ကို control လုပ်နိုင်သည်။

## Features

- **No Passkey Required** — `NoInputNoOutput` D-Bus agent ဖြင့် auto-pair
- **Auto Re-pairing** — Device forget လုပ်ပြီး ပြန်ချိတ်လည်း အဆင်ပြေ
- **WiFi Control** — WiFi scan, connect, disconnect, status စစ်ဆေးနိုင်
- **Real-time Tracking** — D-Bus PropertiesChanged ဖြင့် connect/disconnect event tracking

## Requirements

- Ubuntu/Debian Linux with BlueZ 5.x
- Python 3.8+
- NetworkManager (nmcli)

```bash
sudo apt install bluetooth bluez bluez-tools python3-dbus python3-gi network-manager
```

## Setup

### 1. BlueZ Config (တစ်ကြိမ်ပဲလိုတယ်)

```bash
sudo bash scripts/bluez_config_setup.sh
```

ဒါက `/etc/bluetooth/main.conf` ထဲမှာ:
- `JustWorksRepairing = always` — forget device ပြီး ပြန်ချိတ်ရင် passkey error မဖြစ်စေ
- `AutoEnable = true` — boot တက်ရင် Bluetooth auto-enable
- `DiscoverableTimeout = 0` — အမြဲ discoverable
- `PairableTimeout = 0` — အမြဲ pairable

### 2. Install Dependencies (တစ်ကြိမ်ပဲလိုတယ်)

```bash
sudo bash scripts/install_bluetooth.sh
```

## Run

```bash
# Default (channel 1, name "ROM_Robot")
sudo python3 scripts/bt_server_cli.py

# Custom channel and name
sudo python3 scripts/bt_server_cli.py -c 1 -n "MyRobot"
```

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `-c, --channel` | 1 | RFCOMM channel (1-30) |
| `-n, --name` | ROM_Robot | Bluetooth device name |
| `--clear-paired` | false | Start မတိုင်ခင် paired devices အကုန်ဖျက် |

## Protocol

Bluetooth RFCOMM (SPP) — newline-terminated plain text
- UUID: `00001101-0000-1000-8000-00805F9B34FB`

### Commands

| Command | Response | Description |
|---------|----------|-------------|
| `PING` | `PONG` | Connection test |
| `SEARCH_WIFI` | `WIFI_LIST:SSID\|Signal\|Security\|Known,...` | WiFi networks scan (Known=Y/N) |
| `CONNECT_WIFI:ssid:password` | `CONNECT_OK:INTERNET_OK` / `CONNECT_OK:NO_INTERNET` / `CONNECT_FAIL:reason` | WiFi ချိတ်ဆက် (known network ဆို password မလို) |
| `CURRENT_WIFI` | `CURRENT_WIFI:ssid:INTERNET_OK` / `CURRENT_WIFI:ssid:NO_INTERNET` / `CURRENT_WIFI:NOT_CONNECTED` | လက်ရှိ WiFi စစ်ဆေး + internet status |

## Architecture

```
┌──────────────────────────────────────────────────┐
│              bt_server_cli.py                     │
├──────────────────────────────────────────────────┤
│  NoPinPairingAgent        BluetoothServerCLI     │
│  • D-Bus auto-accept      • RFCOMM Socket        │
│  • NoInputNoOutput        • Command Handler      │
│                            • WiFi (nmcli)         │
├──────────────────────────────────────────────────┤
│              D-Bus (org.bluez)                    │
│  • AgentManager1 — pairing agent                 │
│  • PropertiesChanged — connection tracking       │
├──────────────────────────────────────────────────┤
│           BlueZ 5.x (bluetoothd)                 │
├──────────────────────────────────────────────────┤
│           HCI Adapter (hci0)                     │
├──────────────────────────────────────────────────┤
│           Android Client (RFCOMM SPP)            │
└──────────────────────────────────────────────────┘
```

## Files

| File | Description |
|------|-------------|
| `scripts/bt_server_cli.py` | Main RFCOMM server (Python) |
| `scripts/bluez_config_setup.sh` | BlueZ auto-repairing config setup |
| `scripts/install_bluetooth.sh` | Dependency installer |
| `scripts/test_client.py` | Test client (interactive) |

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Permission denied | `sudo python3 scripts/bt_server_cli.py` |
| Incorrect passkey error | `sudo bash scripts/bluez_config_setup.sh` → restart bluetooth |
| Client can't find server | `bluetoothctl` → `power on` → `discoverable on` |
| Device name wrong | Restart bluetooth → forget device on client → rescan |
