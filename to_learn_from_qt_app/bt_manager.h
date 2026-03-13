#ifndef BT_MANAGER_H
#define BT_MANAGER_H

#include <QObject>
#include <QString>
#include <QBluetoothDeviceDiscoveryAgent>
#include <QBluetoothDeviceInfo>
#include <QBluetoothLocalDevice>
#include <QBluetoothSocket>
#include <QList>
#include <QProcess>
#include <QTimer>
#include <QCoreApplication>

QT_BEGIN_NAMESPACE
namespace Ui {
class BTManager;
}
QT_END_NAMESPACE

/**
 * @brief Bluetooth Manager class for handling BT device discovery and pairing
 * 
 * This class provides functionality to:
 * - Scan for available Bluetooth devices
 * - Pair with selected devices using passphrase
 * - Connect to paired devices
 * 
 * Thread Safety: All public methods are thread-safe
 * Memory Management: Uses Qt parent-child ownership for automatic cleanup
 */
class BluetoothManager : public QObject
{
    Q_OBJECT

public:
    explicit BluetoothManager(QObject *parent = nullptr);
    ~BluetoothManager();

    // Device discovery methods
    void startDeviceScan();
    void stopDeviceScan();
    bool isScanning() const;
    
    // Pairing methods
    void pairDevice(const QBluetoothDeviceInfo &device, const QString &passphrase = "");
    void unpairDevice(const QBluetoothDeviceInfo &device);
    
    // Connection methods
    void connectDevice(const QBluetoothDeviceInfo &device);
    void connectToDevice(const QString &macAddress);  // Direct MAC address connection
    void disconnectDevice(const QBluetoothDeviceInfo &device);
    void disconnectFromDevice();  // Disconnect current connection
    bool isConnected() const;
    
    // Send/receive data
    void sendCommand(const QString &command);
    QString receiveResponse();
    
    // Connection monitoring
    void startKeepalive();
    void stopKeepalive();
    bool checkConnection();
    
    // Get discovered devices
    QList<QBluetoothDeviceInfo> getDiscoveredDevices() const;
    
    // Check if Bluetooth is available and enabled
    bool isBluetoothAvailable() const;
    bool isBluetoothEnabled() const;

signals:
    // Emitted when a new device is discovered
    void deviceDiscovered(const QBluetoothDeviceInfo &device);
    
    // Emitted when device scan completes
    void scanFinished();
    
    // Emitted when device scan encounters an error
    void scanError(const QString &errorMessage);
    
    // Emitted when pairing state changes
    void pairingFinished(const QBluetoothDeviceInfo &device, bool success);
    void pairingError(const QString &errorMessage);
    
    // Emitted when connection state changes
    void connectionStatusChanged(const QBluetoothDeviceInfo &device, bool connected);
    void connectionError(const QString &errorMessage);

private slots:
    // Internal slots for handling discovery agent signals
    void onDeviceDiscovered(const QBluetoothDeviceInfo &device);
    void onScanFinished();
    void onScanError(QBluetoothDeviceDiscoveryAgent::Error error);
    
    // Internal slots for handling pairing
    void onPairingFinished(const QBluetoothAddress &address, QBluetoothLocalDevice::Pairing pairing);
    
    // Internal slots for handling socket connections
    void onSocketConnected();
    void onSocketDisconnected();
    void onSocketError(QBluetoothSocket::SocketError error);
    
    // Keepalive slot
    void onKeepaliveTimer();

private:
    // Discovery agent for scanning devices
    QBluetoothDeviceDiscoveryAgent *m_discoveryAgent;
    
    // Local Bluetooth device for pairing/connection
    QBluetoothLocalDevice *m_localDevice;
    
    // List of discovered devices
    QList<QBluetoothDeviceInfo> m_discoveredDevices;
    
    // Bluetooth socket for RFCOMM connection
    QBluetoothSocket *m_socket;
    
    // Process for rfcomm connection (alternative to Qt socket on Linux)
    QProcess *m_rfcommProcess;
    QString m_rfcommDevice;  // e.g., /dev/rfcomm0
    
    // Connection monitoring
    QTimer *m_keepaliveTimer;
    
    // Currently connected device
    QBluetoothDeviceInfo m_connectedDevice;
    QString m_connectedMacAddress;
    bool m_isConnected;
    
    // Helper methods
    QString getErrorString(QBluetoothDeviceDiscoveryAgent::Error error) const;
    QString getSocketErrorString(QBluetoothSocket::SocketError error) const;
    void clearDiscoveredDevices();
};

#endif // BT_MANAGER_H
