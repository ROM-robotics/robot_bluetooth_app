# bt_server_cli.py — အလုပ်လုပ်ပုံ

## Class Diagram

```mermaid
classDiagram
    class NoPinPairingAgent {
        +server : BluetoothServerCLI
        +Release()
        +AuthorizeService(device, uuid)
        +RequestPinCode(device) str
        +RequestPasskey(device) uint32
        +DisplayPasskey(device, passkey, entered)
        +DisplayPinCode(device, pincode)
        +RequestConfirmation(device, passkey)
        +RequestAuthorization(device)
        +Cancel()
    }

    class BluetoothServerCLI {
        +channel : int
        +device_name : str
        +server_sock : socket
        +client_sock : socket
        +running : bool
        +agent : NoPinPairingAgent
        +mainloop : GLib.MainLoop
        +bus : dbus.SystemBus
        +connected_device : str
        +connection_status : str
        +cleanup_pending : bool
        +connection_attempts : dict
        +last_disconnect_time : dict
        --
        +detect_pairing_issue(mac) bool
        +schedule_pairing_cleanup()
        +remove_device(mac_address) bool
        +on_properties_changed(interface, changed, invalidated, path)
        +on_interfaces_added(path, interfaces)
        +on_interfaces_removed(path, interfaces)
        +setup_dbus_listeners() bool
        +setup_no_pin_agent() bool
        +configure_adapter() bool
        +remove_all_paired_devices()
        +get_adapter_address() str
        +register_sdp() bool
        +kill_process_safely(process)
        +run_nmcli_command(cmd_list, timeout) tuple
        +handle_command(cmd) str
        +handle_client(client_sock, client_info)
        +start() bool
        +stop()
    }

    class main {
        <<function>>
        +argparse(channel, name, clear-paired)
        +signal_handler(SIGINT, SIGTERM)
    }

    NoPinPairingAgent --> BluetoothServerCLI : server reference
    BluetoothServerCLI *-- NoPinPairingAgent : creates agent
    main --> BluetoothServerCLI : creates & starts
```

## Server Startup Flow

```mermaid
flowchart TD
    A[main] --> B[Parse args: channel, name]
    B --> C[Create BluetoothServerCLI]
    C --> D[server.start]

    D --> E["Step 0: remove_all_paired_devices()"]
    E --> F["Step 1: setup_no_pin_agent()"]
    F --> F1[D-Bus MainLoop thread start]
    F --> F2[Register NoPinPairingAgent]
    F --> F3["setup_dbus_listeners()"]

    F1 --> G["Step 2: configure_adapter()"]
    F2 --> G
    F3 --> G
    G --> G1["hciconfig hci0 up"]
    G --> G2["bluetoothctl: power on, discoverable, pairable"]
    G --> G3["hciconfig: piscan, sspmode, name, class"]

    G1 --> H["Step 3: Create RFCOMM socket"]
    G2 --> H
    G3 --> H
    H --> H1["socket.AF_BLUETOOTH, BTPROTO_RFCOMM"]
    H1 --> H2["bind(addr, channel) → listen(1)"]

    H2 --> I["Step 4: register_sdp()"]
    I --> J["Accept Loop — Waiting for connection..."]

    J --> K{client connects?}
    K -- timeout 5s --> L{cleanup_pending?}
    L -- yes --> M[remove_all_paired_devices] --> J
    L -- no --> J
    K -- connected --> N["handle_client(sock, info)"]
    N --> O{recv data}
    O -- data --> P["handle_command(cmd)"]
    P --> Q[send response + newline]
    Q --> O
    O -- no data / error --> R[close client_sock]
    R --> J
```

## Command Handler Flow

```mermaid
flowchart LR
    CMD[handle_command] --> PING{PING}
    CMD --> SEARCH{SEARCH_WIFI}
    CMD --> CONNECT{CONNECT_WIFI:ssid:pass}
    CMD --> CURRENT{CURRENT_WIFI}
    CMD --> UNKNOWN[ERROR:Unknown command]

    PING --> PONG[PONG]

    SEARCH --> S1[Check WiFi radio - enable if disabled]
    S1 --> S2["nmcli device wifi list --rescan yes"]
    S2 --> S3["WIFI_LIST:SSID|Signal|Security,..."]

    CONNECT --> C1[Check WiFi radio]
    C1 --> C2[Verify network in scan results]
    C2 --> C3[Delete old connection profile]
    C3 --> C4["nmcli device wifi connect SSID password PASS"]
    C4 --> C5{Success?}
    C5 -- yes --> C6[CONNECT_OK]
    C5 -- no --> C7["CONNECT_FAIL:reason"]

    CURRENT --> CW1["nmcli -t ACTIVE,SSID dev wifi"]
    CW1 --> CW2{active?}
    CW2 -- yes --> CW3["CURRENT_WIFI:ssid"]
    CW2 -- no --> CW4["CURRENT_WIFI:NOT_CONNECTED"]
```

## Pairing & Auto-Cleanup Flow

```mermaid
flowchart TD
    A[Android Client connects] --> B{D-Bus PropertiesChanged}
    B --> C["on_properties_changed()"]
    C --> D{Connected = true?}

    D -- yes --> E["detect_pairing_issue(mac)"]
    E --> F{3+ connects in 10s OR<br/>disconnect within 2s?}
    F -- yes --> G["⚠ remove_device(mac)"]
    G --> H[Client can pair fresh]
    F -- no --> I["✓ Normal connection"]

    D -- no --> J["Record last_disconnect_time"]
    J --> K{cleanup_pending?}
    K -- yes --> L["remove_device(mac)"]
    K -- no --> M[Wait for reconnect]

    N["NoPinPairingAgent.Cancel()"] --> O["schedule_pairing_cleanup()"]
    O --> K
```

## အကျဥ်းချုပ်

### Server အလုပ်လုပ်ပုံ

1. **Startup** — `main()` → `BluetoothServerCLI` object ဖန်တီးပြီး `start()` ခေါ်သည်
2. **D-Bus Agent** — `NoPinPairingAgent` သည် BlueZ နှင့် register ဖြစ်ပြီး pairing request များကို PIN/passkey မလိုဘဲ auto-accept လုပ်ပေးသည်
3. **Adapter Config** — `bluetoothctl` + `hciconfig` ဖြင့် discoverable/pairable ဖွင့်၊ device name သတ်မှတ်သည်
4. **RFCOMM Socket** — `socket.AF_BLUETOOTH` ဖြင့် RFCOMM channel ပေါ်တွင် listen လုပ်သည်
5. **Accept Loop** — Client ချိတ်ဆက်လာသည်နှင့် `handle_client()` ထဲဝင်ပြီး command-response loop ကို run သည်
6. **Command Protocol** — newline-terminated text commands (PING, SEARCH_WIFI, CONNECT_WIFI, CURRENT_WIFI)
7. **WiFi Control** — `nmcli` ဖြင့် WiFi scan/connect/status စစ်ဆေးသည်
8. **Auto-Cleanup** — Rapid connect/disconnect pattern ကို detect လုပ်ပြီး stale pairing info ကို auto-remove လုပ်ပေးသည်

### Function အုပ်စုများ

| အုပ်စု | Functions | ရည်ရွယ်ချက် |
|---------|-----------|-------------|
| **Pairing Agent** | `NoPinPairingAgent.*` | PIN မလို pairing auto-accept |
| **Server Lifecycle** | `start()`, `stop()`, `main()` | Server စတင်/ရပ် |
| **BT Setup** | `setup_no_pin_agent()`, `configure_adapter()`, `register_sdp()` | Bluetooth adapter ပြင်ဆင် |
| **Connection** | `handle_client()`, `handle_command()` | Client data recv/send loop |
| **WiFi Operations** | `run_nmcli_command()`, `kill_process_safely()` | nmcli command execution |
| **D-Bus Events** | `on_properties_changed()`, `on_interfaces_added/removed()`, `setup_dbus_listeners()` | BT connection event tracking |
| **Pairing Cleanup** | `detect_pairing_issue()`, `remove_device()`, `remove_all_paired_devices()`, `schedule_pairing_cleanup()` | Stale pairing auto-fix |
| **Utility** | `get_adapter_address()` | Adapter MAC address ရယူ |
