# ROM Bluetooth Server

Ubuntu Server - Android device မှ Bluetooth ဖြင့် WiFi ကို control လုပ်နိုင်သည်။

## Build

```bash
cd rom_bt_server
mkdir build && cd build
cmake ..
make
```

## Run

```bash
./rom_bt_eth_server
```

## Commands

Bluetooth (RFCOMM) သို့မဟုတ် Ethernet (TCP port 7358) မှ အောက်ပါ commands များကို ပေးပို့နိုင်သည်။

| Command | Format | Response | Description |
|---------|--------|----------|-------------|
| WiFi ရှာဖွေ | `search_wifi` | `WIFI_LIST:SSID1,SSID2,SSID3` | နီးနားရှိ WiFi networks များကို ရှာဖွေပေးသည် |
| WiFi ချိတ်ဆက် | `connect_wifi:SSID:PASSWORD` | `CONNECT_OK` / `CONNECT_FAIL` | သတ်မှတ်ထားသော WiFi သို့ ချိတ်ဆက်သည် |
| WiFi ဖြုတ် | `disconnect_wifi` | `DISCONNECT_OK` / `DISCONNECT_FAIL` | လက်ရှိ WiFi ကို ဖြုတ်သည် |
| လက်ရှိ WiFi | `current_wifi` | `CURRENT_WIFI:SSID` / `CURRENT_WIFI:NOT_CONNECTED` | လက်ရှိ ချိတ်ဆက်ထားသော WiFi ကို ပြသည် |

## Connection Methods

### Bluetooth (RFCOMM)
- UUID: `00001101-0000-1000-8000-00805F9B34FB`
- Android ဘက်မှ Standard Serial Port Service UUID နဲ့ ချိတ်ဆက်ပါ

#### Bluetooth Auto-Pairing Setup

Server run မတိုင်ခင် Bluetooth auto-pairing agent ကို စတင်ပါ:

```bash
# Method 1: Simple setup script
sudo ./scripts/bluetooth_setup.sh

# Method 2: Expect script (auto-accept all requests)
sudo apt install expect  # လိုအပ်ရင် install လုပ်ပါ
./scripts/bluetooth_agent.exp
```

သို့မဟုတ် manual setup:
```bash
bluetoothctl
> power on
> discoverable on
> pairable on
> agent NoInputNoOutput
> default-agent
```

Android ဘက်မှ:
1. Bluetooth Settings သွားပါ
2. Ubuntu device ကို ရှာပြီး pair လုပ်ပါ (auto-accept ဖြစ်မည်)
3. App မှ UUID နဲ့ connect လုပ်ပါ

### Ethernet (TCP)
- Port: `7358`
- LAN cable ချိတ်ပြီး server IP address ကို port 7358 နဲ့ ချိတ်ဆက်ပါ
- Example: `nc 192.168.1.100 7358`

## Requirements

- Qt 6.5+
- Qt Bluetooth module
- Qt Network module
- NetworkManager (nmcli)
- Ubuntu/Linux
