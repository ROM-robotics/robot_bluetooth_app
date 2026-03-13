Here’s a **clean, Linux-centric answer** 👍

---

## 1️⃣ Types of Bluetooth services (Linux / BlueZ)

Bluetooth services are grouped by **Bluetooth type**.

---

### A) **Bluetooth Classic (BR/EDR) Services**

(Streaming / serial style)

| Service                       | What it does            |
| ----------------------------- | ----------------------- |
| **SPP (Serial Port Profile)** | Virtual serial cable    |
| **RFCOMM**                    | Transport used by SPP   |
| **A2DP**                      | Audio streaming (music) |
| **HSP / HFP**                 | Headset / Hands-free    |
| **HID**                       | Keyboard, mouse         |
| **PAN**                       | Network over Bluetooth  |
| **OPP**                       | File transfer           |

📌 Linux tools

* `bluetoothctl`
* `rfcomm`
* `bluez-utils`

---

### B) **Bluetooth Low Energy (BLE) Services**

(Modern, embedded, low power)

| Service type               | Description                      |
| -------------------------- | -------------------------------- |
| **Standard GATT Services** | Battery, Device Info, Heart Rate |
| **Custom GATT Services**   | Your own UUID                    |
| **Characteristics**        | Read / Write / Notify values     |

📌 Linux tools

* BlueZ (DBus)
* `btmgmt`
* `bleak` (Python)
* `gattlib` (C/C++)

---

## 2️⃣ Bluetooth workflows in Linux (Very important)

---

## Workflow 1️⃣ — Classic Bluetooth (RFCOMM / SPP)

```text
Scan
 → Pair (PIN / confirm)
   → Connect
     → /dev/rfcomm0
       → Read / Write bytes
```

✔ Looks like serial
❌ Needs pairing
❌ Passkey dialogs
❌ Not good for headless embedded

---

## Workflow 2️⃣ — BLE (No pairing, most embedded use) ⭐⭐⭐

```text
Advertise
 → Scan
   → Connect
     → GATT Read / Write / Notify
```

✔ No passkey
✔ No confirm
✔ Best for embedded / robots

---

## Workflow 3️⃣ — BLE with pairing (Just Works)

```text
Advertise
 → Scan
   → Pair (auto)
     → Connect
       → Secure GATT
```

✔ Still no UI
✔ Optional bonding

---

## Workflow 4️⃣ — Audio / HID (Desktop usage)

```text
Scan
 → Pair
   → Trust
     → Connect
       → Audio / Input device
```

Handled mostly by:

* PipeWire / PulseAudio
* BlueZ

---

## 3️⃣ Summary table (Linux)

| Item                | Count                 |
| ------------------- | --------------------- |
| Bluetooth types     | **2** (Classic, BLE)  |
| Main service models | **2** (Profile, GATT) |
| Common workflows    | **4**                 |
| Embedded-friendly   | **BLE GATT**          |

---

## One-line takeaway

> **Linux Bluetooth = Classic profiles OR BLE GATT**
> **Embedded & no-UI = BLE GATT workflow**
> **RFCOMM/SPP = legacy**

---

If you want, I can next give you:

* 📊 **Diagram**
* 🧠 **Decision chart (Which to choose)**
* 💻 **Exact Linux commands per workflow**

Just tell me.


# Default settings (channel 1, name "ROM_Robot")
sudo python3 rom_bt_eth_server/bt_server_cli.py

# Custom channel and name
sudo python3 rom_bt_eth_server/bt_server_cli.py -c 2 -n "MyRobot"

# Help
python3 rom_bt_eth_server/bt_server_cli.py --help