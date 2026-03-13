# ROM BT ETH - Application Architecture & Workflow

ROM Robotics Bluetooth and Ethernet Manager — Qt6 C++ GUI application for configuring WiFi, Bluetooth and Ethernet connections on autonomous mobile robots.

---

## 1. System Overview

```mermaid
graph TB
    subgraph "ROM BT ETH App (Qt6 C++)"
        MW[MainWindow<br/>QStackedWidget - 7 Pages]
        BTM[BluetoothManager]
        ETM[EthernetManager]
        TCP[QTcpSocket]
        WFD[WiFiSelectionDialog]
    end

    subgraph "Robot Server"
        BTS[Bluetooth RFCOMM Server]
        TCPS[TCP Socket Server]
        WIFI[WiFi Manager]
    end

    MW --> BTM
    MW --> ETM
    MW --> TCP
    MW --> WFD

    BTM -- "RFCOMM" --> BTS
    TCP -- "TCP/IP" --> TCPS
    TCPS --> WIFI
```

---

## 2. Class Diagram

```mermaid
classDiagram
    class MainWindow {
        -BluetoothManager* m_btManager
        -EthernetManager* m_ethManager
        -QTcpSocket* m_tcpSocket
        -QString m_connectionType
        -QStringList m_wifiNetworkList
        -QByteArray m_tcpBuffer
        +setupConnections()
        +setupManagers()
        +showBindingPage()
        +showModePage()
        +showBtPage()
        +showEthPage()
        +showMainMenuPage()
        +showSettingsPage()
        +showWifiSettingsPage()
        +connectToRobotTCP()
        +sendCommand()
        +handleServerResponse()
    }

    class BluetoothManager {
        -QBluetoothDeviceDiscoveryAgent* m_discoveryAgent
        -QBluetoothLocalDevice* m_localDevice
        -QBluetoothSocket* m_socket
        -QProcess* m_rfcommProcess
        -QTimer* m_keepaliveTimer
        -bool m_isConnected
        +startDeviceScan()
        +stopDeviceScan()
        +pairDevice()
        +connectDevice()
        +connectToDevice(macAddress)
        +disconnectFromDevice()
        +sendCommand()
        +receiveResponse()
        +startKeepalive()
    }

    class EthernetManager {
        -QProcess* m_process
        -NetworkConfig m_currentConfig
        +setStaticIP(config)
        +enableDHCP()
        +getCurrentConfig()
        +getAvailableInterfaces()
        +isValidIPAddress()
        +isInterfaceUp()
    }

    class WiFiSelectionDialog {
        -QListWidget* m_listWidget
        -QLineEdit* m_passwordEdit
        -QString m_selectedSSID
        -QString m_password
        +getSelectedSSID()
        +getPassword()
    }

    class NetworkConfig {
        +QString interfaceName
        +QString ipAddress
        +QString subnetMask
        +QString gateway
        +QString primaryDns
        +QString secondaryDns
        +bool useDhcp
    }

    MainWindow --> BluetoothManager : uses
    MainWindow --> EthernetManager : uses
    MainWindow --> WiFiSelectionDialog : creates
    EthernetManager --> NetworkConfig : contains
```

---

## 3. Page Navigation Flow (QStackedWidget)

```mermaid
stateDiagram-v2
    [*] --> BindingPage: App Start

    BindingPage: 0 - Binding Page<br/>(Robot Name Input)
    ModePage: 1 - Mode Selection<br/>(BT / Ethernet)
    BtPage: 2 - Bluetooth Page<br/>(MAC Address Input)
    EthPage: 3 - Ethernet Page<br/>(IP + Port Input)
    MainMenuPage: 4 - Main Menu<br/>(Guide / Gallery / Settings / Function)
    SettingsPage: 5 - Settings Page<br/>(WiFi / Data / Remote / Alarm)
    WifiSettingsPage: 6 - WiFi Settings<br/>(AP Mode / WiFi Search)

    BindingPage --> ModePage: bindButton click
    ModePage --> BindingPage: backButton2
    ModePage --> BtPage: btModeCard click
    ModePage --> EthPage: ethernetModeCard click
    BtPage --> ModePage: backButton3
    EthPage --> ModePage: backButton4
    BtPage --> MainMenuPage: BT Connected
    EthPage --> MainMenuPage: TCP Connected
    MainMenuPage --> BindingPage: backButton5
    MainMenuPage --> SettingsPage: emplaceCard click
    SettingsPage --> MainMenuPage: backButton6
    SettingsPage --> WifiSettingsPage: wifiSettingItem click
    WifiSettingsPage --> SettingsPage: backButton7
```

---

## 4. Bluetooth Connection Flow

```mermaid
sequenceDiagram
    actor User
    participant MW as MainWindow
    participant BTM as BluetoothManager
    participant Robot as Robot (BT Server)

    User->>MW: Enter MAC Address
    User->>MW: Click "Pair and Connect"
    MW->>MW: Validate MAC format<br/>(XX:XX:XX:XX:XX:XX)
    MW->>BTM: connectToDevice(macAddress)

    alt Using QBluetoothSocket
        BTM->>Robot: RFCOMM Connect
        Robot-->>BTM: Connected
        BTM->>MW: connectionStatusChanged(device, true)
    else Using rfcomm process (Linux)
        BTM->>Robot: rfcomm connect /dev/rfcomm0
        Robot-->>BTM: Connected
        BTM->>MW: connectionStatusChanged(device, true)
    end

    MW->>MW: m_connectionType = "bt"
    MW->>MW: showMainMenuPage()

    Note over BTM,Robot: Keepalive timer monitors connection

    Robot-->>BTM: Disconnected
    BTM->>MW: connectionStatusChanged(device, false)
    MW->>MW: Show warning dialog
```

---

## 5. Ethernet (TCP) Connection Flow

```mermaid
sequenceDiagram
    actor User
    participant MW as MainWindow
    participant TCP as QTcpSocket
    participant Robot as Robot (TCP Server)

    User->>MW: Enter IP Address + Port
    User->>MW: Click "Connect"
    MW->>MW: Validate IP & Port

    MW->>TCP: connectToHost(ip, port)
    TCP->>Robot: TCP SYN
    Robot-->>TCP: TCP SYN-ACK

    alt Connected (within 5s)
        TCP-->>MW: connected signal
        MW->>MW: m_connectionType = "ethernet"
        MW->>MW: showMainMenuPage()
    else Timeout
        MW->>MW: Show "Connection Failed" error
        MW->>TCP: deleteLater()
    end

    Note over TCP,Robot: Bidirectional data exchange

    loop Data Exchange
        MW->>TCP: sendCommand(command)
        TCP->>Robot: [size][type][data]
        Robot-->>TCP: [size][type][data]
        TCP-->>MW: readyRead signal
        MW->>MW: processPackets()
        MW->>MW: handleServerResponse()
    end
```

---

## 6. WiFi Management Flow (via TCP)

```mermaid
sequenceDiagram
    actor User
    participant MW as MainWindow
    participant TCP as QTcpSocket
    participant Robot as Robot Server
    participant WFD as WiFiSelectionDialog

    User->>MW: Click "Search WiFi"
    MW->>MW: Check Ethernet connection

    MW->>TCP: sendCommand("SEARCH_WIFI")
    TCP->>Robot: SEARCH_WIFI
    MW->>MW: UI: "Searching..."

    Robot->>Robot: nmcli / iwlist scan
    Robot-->>TCP: WIFI_LIST:ssid1:signal1:sec1:connected,...
    TCP-->>MW: readyRead
    MW->>MW: handleServerResponse()
    MW->>MW: Parse network list

    MW->>WFD: Show WiFi dialog
    User->>WFD: Select SSID + Enter Password
    WFD-->>MW: selectedSSID, password

    MW->>TCP: sendCommand("CONNECT_WIFI:ssid:password")
    TCP->>Robot: CONNECT_WIFI:ssid:password
    Robot->>Robot: nmcli connect
    Robot-->>TCP: CONNECT_OK:ssid
    TCP-->>MW: readyRead
    MW->>MW: Update WiFi status labels

    MW->>TCP: sendCommand("CURRENT_WIFI")
    TCP->>Robot: CURRENT_WIFI
    Robot-->>TCP: CURRENT_WIFI:ssid:ip
    TCP-->>MW: Show connected WiFi info
```

---

## 7. TCP Packet Protocol

```mermaid
graph LR
    subgraph "Packet Structure"
        A["quint32<br/>Packet Size"] --> B["QString<br/>Type<br/>(COMMAND/RESPONSE)"] --> C["QByteArray<br/>Data"]
    end

    subgraph "Commands (App → Robot)"
        D["SEARCH_WIFI"]
        E["CONNECT_WIFI:ssid:password"]
        F["CURRENT_WIFI"]
    end

    subgraph "Responses (Robot → App)"
        G["WIFI_LIST:ssid:signal:security:connected,..."]
        H["CONNECT_OK:ssid"]
        I["CURRENT_WIFI:ssid:ip"]
        J["ERROR:message"]
    end
```

---

## 8. Application Startup Flow

```mermaid
flowchart TD
    A[main.cpp] --> B[QApplication Create]
    B --> C[Load Roboto Condensed Fonts<br/>Regular / Bold / Medium / Light]
    C --> D[Set Application Font]
    D --> E[MainWindow Create]

    E --> F[setupManagers]
    F --> F1[Create BluetoothManager]
    F --> F2[Create EthernetManager]
    F --> F3[Connect BT Signals/Slots]
    F --> F4[Connect ETH Signals/Slots]

    E --> G[setupConnections]
    G --> G1[Connect Back Buttons × 6]
    G --> G2[Connect Bind Button]
    G --> G3[Connect BT Pair Button]
    G --> G4[Connect ETH Apply Button]
    G --> G5[Connect WiFi Search Button]
    G --> G6[Make Cards Clickable<br/>Mode / MainMenu / Settings / WiFi]

    E --> H[setupRobotImage]
    E --> I[showBindingPage - Index 0]
    I --> J[App Ready]
```

---

## 9. Build & Deployment Targets

```mermaid
graph TD
    subgraph "Source Code"
        SRC[rom_bt_eth/]
    end

    subgraph "Build Targets"
        SRC --> ANDROID["Android APK<br/>(arm64-v8a)<br/>Qt 6.8.3 + NDK 26"]
        SRC --> LINUX_X86["Linux x86_64<br/>AppImage<br/>Qt 6.8.3 gcc_64"]
        SRC --> LINUX_ARM["Linux ARM64<br/>.deb Package<br/>Qt 6.4 Debian 12"]
    end

    subgraph "CI/CD (GitHub Actions)"
        TAG["Git Tag Push<br/>v*.*.*"] --> WF[multi_platform_release.yml]
        WF --> ANDROID
        WF --> LINUX_X86
        WF --> LINUX_ARM
        ANDROID --> REL[GitHub Release]
        LINUX_X86 --> REL
        LINUX_ARM --> REL
    end
```

---

## 10. Signal-Slot Connection Map

```mermaid
graph LR
    subgraph "BluetoothManager Signals"
        BT1[deviceDiscovered]
        BT2[scanFinished]
        BT3[scanError]
        BT4[pairingFinished]
        BT5[pairingError]
        BT6[connectionStatusChanged]
        BT7[connectionError]
    end

    subgraph "MainWindow Slots"
        MW1[onDeviceDiscovered]
        MW2[onScanFinished]
        MW3[onScanError]
        MW4[onPairingFinished]
        MW5[onPairingError]
        MW6[onConnectionStatusChanged]
        MW7[onConnectionError]
    end

    BT1 --> MW1
    BT2 --> MW2
    BT3 --> MW3
    BT4 --> MW4
    BT5 --> MW5
    BT6 --> MW6
    BT7 --> MW7

    subgraph "EthernetManager Signals"
        ETH1[configurationApplied]
        ETH2[configurationError]
    end

    subgraph "MainWindow ETH Slots"
        MWE1[onEthConfigApplied]
        MWE2[onEthConfigError]
    end

    ETH1 --> MWE1
    ETH2 --> MWE2

    subgraph "QTcpSocket Signals"
        TCP1[connected]
        TCP2[disconnected]
        TCP3[readyRead]
        TCP4[errorOccurred]
    end

    subgraph "MainWindow TCP Slots"
        MWT1[onTcpConnected]
        MWT2[onTcpDisconnected]
        MWT3[onTcpReadyRead]
        MWT4[onTcpError]
    end

    TCP1 --> MWT1
    TCP2 --> MWT2
    TCP3 --> MWT3
    TCP4 --> MWT4
```
