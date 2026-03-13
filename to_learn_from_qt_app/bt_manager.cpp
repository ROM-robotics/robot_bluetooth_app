#include "bt_manager.h"
#include <QDebug>
#include <QProcess>
#include <QFile>
#include <QTextStream>

BluetoothManager::BluetoothManager(QObject *parent)
    : QObject(parent)
    , m_discoveryAgent(nullptr)
    , m_localDevice(nullptr)
    , m_socket(nullptr)
    , m_rfcommProcess(nullptr)
    , m_rfcommDevice("")
    , m_isConnected(false)
{
    // Initialize Bluetooth local device
    m_localDevice = new QBluetoothLocalDevice(this);
    
    // Check if Bluetooth is available
    if (!m_localDevice->isValid()) {
        qWarning() << "Bluetooth is not available on this device";
        return;
    }
    
    // Initialize discovery agent
    m_discoveryAgent = new QBluetoothDeviceDiscoveryAgent(this);
    
    // Connect discovery agent signals
    connect(m_discoveryAgent, &QBluetoothDeviceDiscoveryAgent::deviceDiscovered,
            this, &BluetoothManager::onDeviceDiscovered);
    connect(m_discoveryAgent, &QBluetoothDeviceDiscoveryAgent::finished,
            this, &BluetoothManager::onScanFinished);
    connect(m_discoveryAgent, &QBluetoothDeviceDiscoveryAgent::errorOccurred,
            this, &BluetoothManager::onScanError);
    
    // Connect pairing signals
    connect(m_localDevice, &QBluetoothLocalDevice::pairingFinished,
            this, &BluetoothManager::onPairingFinished);
    
    qDebug() << "BluetoothManager initialized successfully";
}

BluetoothManager::~BluetoothManager()
{
    // Stop scanning if active
    if (m_discoveryAgent && m_discoveryAgent->isActive()) {
        m_discoveryAgent->stop();
    }
    
    // Disconnect if connected
    disconnectFromDevice();
    
    // Disconnect socket if connected
    if (m_socket && m_socket->state() == QBluetoothSocket::SocketState::ConnectedState) {
        m_socket->disconnectFromService();
    }
    
    qDebug() << "BluetoothManager destroyed";
}

void BluetoothManager::startDeviceScan()
{
    if (!isBluetoothAvailable()) {
        emit scanError("Bluetooth is not available");
        return;
    }
    
    if (!isBluetoothEnabled()) {
        emit scanError("Bluetooth is not enabled");
        return;
    }
    
    if (m_discoveryAgent->isActive()) {
        qDebug() << "Device scan already in progress";
        return;
    }
    
    // Clear previous results
    clearDiscoveredDevices();
    
    qDebug() << "Starting Bluetooth device scan...";
    m_discoveryAgent->start(QBluetoothDeviceDiscoveryAgent::ClassicMethod);
}

void BluetoothManager::stopDeviceScan()
{
    if (m_discoveryAgent && m_discoveryAgent->isActive()) {
        qDebug() << "Stopping Bluetooth device scan";
        m_discoveryAgent->stop();
    }
}

bool BluetoothManager::isScanning() const
{
    return m_discoveryAgent && m_discoveryAgent->isActive();
}

void BluetoothManager::pairDevice(const QBluetoothDeviceInfo &device, const QString &passphrase)
{
    if (!isBluetoothAvailable()) {
        emit pairingError("Bluetooth is not available");
        return;
    }
    
    qDebug() << "Attempting to pair with device:" << device.name() << device.address().toString();
    
    // Note: Qt's Bluetooth API doesn't directly support custom passphrase input
    // The system will typically prompt the user for passphrase through OS dialog
    // For automated pairing, you may need platform-specific implementations
    
    m_localDevice->requestPairing(device.address(), QBluetoothLocalDevice::Paired);
}

void BluetoothManager::unpairDevice(const QBluetoothDeviceInfo &device)
{
    if (!isBluetoothAvailable()) {
        emit pairingError("Bluetooth is not available");
        return;
    }
    
    qDebug() << "Unpairing device:" << device.name();
    m_localDevice->requestPairing(device.address(), QBluetoothLocalDevice::Unpaired);
}

void BluetoothManager::connectDevice(const QBluetoothDeviceInfo &device)
{
    qDebug() << "Connecting to device:" << device.name();
    
    // Check if device is paired
    QBluetoothLocalDevice::Pairing pairingStatus = m_localDevice->pairingStatus(device.address());
    
    if (pairingStatus == QBluetoothLocalDevice::Unpaired) {
        emit connectionError("Device is not paired. Please pair first.");
        return;
    }
    
    // Store device info
    m_connectedDevice = device;
    
    // Create socket if not already created
    if (!m_socket) {
        m_socket = new QBluetoothSocket(QBluetoothServiceInfo::RfcommProtocol, this);
        
        // Connect socket signals
        connect(m_socket, &QBluetoothSocket::connected,
                this, &BluetoothManager::onSocketConnected);
        connect(m_socket, &QBluetoothSocket::disconnected,
                this, &BluetoothManager::onSocketDisconnected);
        connect(m_socket, &QBluetoothSocket::errorOccurred,
                this, &BluetoothManager::onSocketError);
    }
    
    // Connect directly using device address and Serial Port UUID
    // Note: On Linux, Qt Bluetooth requires UUID (not channel number) due to D-Bus API
    QBluetoothUuid serialPortUuid(QBluetoothUuid::ServiceClassUuid::SerialPort);
    qDebug() << "Connecting to" << device.address().toString() << "using Serial Port UUID";
    m_socket->connectToService(device.address(), serialPortUuid);
}

void BluetoothManager::connectToDevice(const QString &macAddress)
{
    qDebug() << "Connecting to MAC address via rfcomm:" << macAddress;
    
    m_connectedMacAddress = macAddress;
    m_rfcommDevice = "/dev/rfcomm0";
    
    // Use helper script that handles sudo and permissions
    // Get absolute path to script (in source tree)
    QString scriptPath = "/home/mr_robot/Desktop/Git/BluetoothEthernetSystem/rom_bt_eth/scripts/rfcomm_connect.sh";
    
    if (!QFile::exists(scriptPath)) {
        emit connectionError("Helper script not found at: " + scriptPath);
        return;
    }
    
    // Find terminal emulator
    QString terminalCmd;
    if (QFile::exists("/usr/bin/x-terminal-emulator")) {
        terminalCmd = "x-terminal-emulator";
    } else if (QFile::exists("/usr/bin/xterm")) {
        terminalCmd = "xterm";
    } else if (QFile::exists("/usr/bin/gnome-terminal")) {
        terminalCmd = "gnome-terminal";
    } else {
        emit connectionError("No terminal emulator found. Please install xterm.");
        return;
    }
    
    QStringList args;
    if (terminalCmd.contains("gnome-terminal")) {
        args << "--" << "bash" << scriptPath << m_rfcommDevice << macAddress << "1";
    } else {
        args << "-e" << QString("bash %1 %2 %3 1").arg(scriptPath).arg(m_rfcommDevice).arg(macAddress);
    }
    
    if (m_rfcommProcess) {
        m_rfcommProcess->kill();
        m_rfcommProcess->deleteLater();
    }
    
    m_rfcommProcess = new QProcess(this);
    
    connect(m_rfcommProcess, &QProcess::started, this, [this]() {
        qDebug() << "Terminal process started, checking for connection...";
    });
    
    connect(m_rfcommProcess, QOverload<int, QProcess::ExitStatus>::of(&QProcess::finished),
            this, [this](int exitCode, QProcess::ExitStatus exitStatus) {
        qDebug() << "Terminal process finished with code:" << exitCode;
        if (m_isConnected) {
            m_isConnected = false;
            stopKeepalive();
            
            QBluetoothAddress addr(m_connectedMacAddress);
            QBluetoothDeviceInfo device(addr, "Robot", QBluetoothDeviceInfo::MiscellaneousDevice);
            emit connectionStatusChanged(device, false);
        }
    });
    
    connect(m_rfcommProcess, &QProcess::errorOccurred,
            this, [this](QProcess::ProcessError error) {
        qDebug() << "Process error:" << error;
        m_isConnected = false;
        emit connectionError("Failed to start terminal.");
    });
    
    qDebug() << "Starting terminal:" << terminalCmd << args;
    m_rfcommProcess->start(terminalCmd, args);
    
    // Check device file periodically to detect connection
    QTimer *checkTimer = new QTimer(this);
    connect(checkTimer, &QTimer::timeout, this, [this, checkTimer]() {
        if (!m_rfcommProcess || m_rfcommProcess->state() != QProcess::Running) {
            checkTimer->stop();
            checkTimer->deleteLater();
            return;
        }
        
        QFile device(m_rfcommDevice);
        if (device.exists() && !m_isConnected) {
            qDebug() << "rfcomm device exists, connection established!";
            m_isConnected = true;
            checkTimer->stop();
            checkTimer->deleteLater();
            
            QBluetoothAddress addr(m_connectedMacAddress);
            QBluetoothDeviceInfo deviceInfo(addr, "Robot", QBluetoothDeviceInfo::MiscellaneousDevice);
            m_connectedDevice = deviceInfo;
            
            emit connectionStatusChanged(deviceInfo, true);
            
            // Start keepalive after 3 seconds to allow permissions to be set
            QTimer::singleShot(3000, this, [this]() {
                if (m_isConnected) {
                    qDebug() << "Starting keepalive timer";
                    startKeepalive();
                }
            });
        }
    });
    checkTimer->start(500);  // Check every 500ms
}

void BluetoothManager::disconnectFromDevice()
{
    qDebug() << "Disconnecting rfcomm device";
    
    // Stop keepalive
    stopKeepalive();
    
    if (m_rfcommProcess && m_rfcommProcess->state() == QProcess::Running) {
        m_rfcommProcess->kill();
        m_rfcommProcess->waitForFinished();
    }
    
    // Release rfcomm device
    if (!m_rfcommDevice.isEmpty()) {
        QProcess releaseProc;
        releaseProc.start("rfcomm", QStringList() << "release" << m_rfcommDevice);
        releaseProc.waitForFinished();
    }
    
    m_isConnected = false;
    
    if (m_connectedDevice.isValid()) {
        emit connectionStatusChanged(m_connectedDevice, false);
    }
}

void BluetoothManager::sendCommand(const QString &command)
{
    if (!m_isConnected || m_rfcommDevice.isEmpty()) {
        qWarning() << "Not connected, cannot send command";
        return;
    }
    
    QFile device(m_rfcommDevice);
    if (!device.exists()) {
        qWarning() << "rfcomm device does not exist:" << m_rfcommDevice;
        return;
    }
    
    if (device.open(QIODevice::WriteOnly)) {
        // Write command with newline terminator
        QByteArray data = (command + "\n").toUtf8();
        qint64 written = device.write(data);
        device.flush();
        device.close();
        
        if (written == data.size()) {
            qDebug() << "Sent command:" << command;
        } else {
            qWarning() << "Failed to write complete command, wrote" << written << "of" << data.size() << "bytes";
        }
    } else {
        qWarning() << "Failed to open rfcomm device for writing:" << device.errorString();
    }
}

QString BluetoothManager::receiveResponse()
{
    if (!m_isConnected || m_rfcommDevice.isEmpty()) {
        qWarning() << "Not connected, cannot receive response";
        return "";
    }
    
    QFile device(m_rfcommDevice);
    if (device.open(QIODevice::ReadOnly)) {
        QTextStream in(&device);
        QString response = in.readAll();
        device.close();
        qDebug() << "Received response:" << response;
        return response;
    } else {
        qWarning() << "Failed to open rfcomm device for reading";
        return "";
    }
}

// Connection monitoring methods
void BluetoothManager::startKeepalive()
{
    qDebug() << "Starting keepalive timer";
    
    if (!m_keepaliveTimer) {
        m_keepaliveTimer = new QTimer(this);
        connect(m_keepaliveTimer, &QTimer::timeout, this, &BluetoothManager::onKeepaliveTimer);
    }
    
    // Send keepalive every 3 seconds (less than server's 5 second timeout)
    m_keepaliveTimer->start(3000);
}

void BluetoothManager::stopKeepalive()
{
    qDebug() << "Stopping keepalive timer";
    
    if (m_keepaliveTimer && m_keepaliveTimer->isActive()) {
        m_keepaliveTimer->stop();
    }
}

bool BluetoothManager::checkConnection()
{
    // Check if rfcomm device exists and is readable
    if (m_rfcommDevice.isEmpty()) {
        return false;
    }
    
    QFile device(m_rfcommDevice);
    if (!device.exists()) {
        qWarning() << "rfcomm device does not exist:" << m_rfcommDevice;
        return false;
    }
    
    return m_isConnected;
}

void BluetoothManager::onKeepaliveTimer()
{
    if (!m_isConnected) {
        stopKeepalive();
        return;
    }
    
    // Check if connection is still alive
    if (!checkConnection()) {
        qWarning() << "Connection check failed, disconnecting";
        m_isConnected = false;
        stopKeepalive();
        
        QBluetoothAddress addr(m_connectedMacAddress);
        QBluetoothDeviceInfo device(addr, "Robot", QBluetoothDeviceInfo::MiscellaneousDevice);
        emit connectionStatusChanged(device, false);
        return;
    }
    
    // Send a simple ping command to keep connection alive
    // Use try-catch style error handling
    qDebug() << "Sending keepalive ping";
    try {
        sendCommand("PING");
    } catch (...) {
        qWarning() << "Exception caught during keepalive ping";
        stopKeepalive();
    }
}

void BluetoothManager::disconnectDevice(const QBluetoothDeviceInfo &device)
{
    qDebug() << "Disconnecting from device:" << device.name();
    
    if (m_socket && m_socket->state() == QBluetoothSocket::SocketState::ConnectedState) {
        m_socket->disconnectFromService();
    }
    
    m_isConnected = false;
    emit connectionStatusChanged(device, false);
}

bool BluetoothManager::isConnected() const
{
    return m_isConnected && m_socket && 
           m_socket->state() == QBluetoothSocket::SocketState::ConnectedState;
}

QList<QBluetoothDeviceInfo> BluetoothManager::getDiscoveredDevices() const
{
    return m_discoveredDevices;
}

bool BluetoothManager::isBluetoothAvailable() const
{
    return m_localDevice && m_localDevice->isValid();
}

bool BluetoothManager::isBluetoothEnabled() const
{
    if (!isBluetoothAvailable()) {
        return false;
    }
    
    return m_localDevice->hostMode() != QBluetoothLocalDevice::HostPoweredOff;
}

// Private slots

void BluetoothManager::onDeviceDiscovered(const QBluetoothDeviceInfo &device)
{
    // Avoid duplicates
    for (const QBluetoothDeviceInfo &existingDevice : m_discoveredDevices) {
        if (existingDevice.address() == device.address()) {
            return;
        }
    }
    
    qDebug() << "Device discovered:" << device.name() << "(" << device.address().toString() << ")";
    
    m_discoveredDevices.append(device);
    emit deviceDiscovered(device);
}

void BluetoothManager::onScanFinished()
{
    qDebug() << "Device scan finished. Found" << m_discoveredDevices.size() << "devices";
    emit scanFinished();
}

void BluetoothManager::onScanError(QBluetoothDeviceDiscoveryAgent::Error error)
{
    QString errorMessage = getErrorString(error);
    qWarning() << "Device scan error:" << errorMessage;
    emit scanError(errorMessage);
}

void BluetoothManager::onPairingFinished(const QBluetoothAddress &address, QBluetoothLocalDevice::Pairing pairing)
{
    // Find the device info
    QBluetoothDeviceInfo deviceInfo;
    bool found = false;
    
    for (const QBluetoothDeviceInfo &device : m_discoveredDevices) {
        if (device.address() == address) {
            deviceInfo = device;
            found = true;
            break;
        }
    }
    
    bool success = (pairing == QBluetoothLocalDevice::Paired || pairing == QBluetoothLocalDevice::AuthorizedPaired);
    
    qDebug() << "Pairing finished for" << address.toString() << "- Success:" << success;
    
    if (found) {
        emit pairingFinished(deviceInfo, success);
    } else {
        // Pairing failed or device not found
        if (!success) {
            emit pairingError("Pairing failed for device: " + address.toString());
        }
    }
}

// Private helper methods

QString BluetoothManager::getErrorString(QBluetoothDeviceDiscoveryAgent::Error error) const
{
    switch (error) {
        case QBluetoothDeviceDiscoveryAgent::PoweredOffError:
            return "Bluetooth adapter is powered off";
        case QBluetoothDeviceDiscoveryAgent::InputOutputError:
            return "I/O error occurred during device discovery";
        case QBluetoothDeviceDiscoveryAgent::InvalidBluetoothAdapterError:
            return "Invalid Bluetooth adapter";
        case QBluetoothDeviceDiscoveryAgent::UnsupportedPlatformError:
            return "Device discovery is not supported on this platform";
        case QBluetoothDeviceDiscoveryAgent::UnsupportedDiscoveryMethod:
            return "Discovery method not supported";
        default:
            return "Unknown error occurred";
    }
}

void BluetoothManager::clearDiscoveredDevices()
{
    m_discoveredDevices.clear();
}

// Socket connection handlers

void BluetoothManager::onSocketConnected()
{
    qDebug() << "Socket connected successfully";
    m_isConnected = true;
    emit connectionStatusChanged(m_connectedDevice, true);
}

void BluetoothManager::onSocketDisconnected()
{
    qDebug() << "Socket disconnected";
    m_isConnected = false;
    
    emit connectionStatusChanged(m_connectedDevice, false);
}

void BluetoothManager::onSocketError(QBluetoothSocket::SocketError error)
{
    QString errorMessage = getSocketErrorString(error);
    qWarning() << "Socket error:" << errorMessage;
    
    m_isConnected = false;
    
    emit connectionError(errorMessage);
}

QString BluetoothManager::getSocketErrorString(QBluetoothSocket::SocketError error) const
{
    // Simply return the error description from the socket itself
    if (m_socket) {
        return m_socket->errorString();
    }
    return "Socket error occurred: " + QString::number(static_cast<int>(error));
}
