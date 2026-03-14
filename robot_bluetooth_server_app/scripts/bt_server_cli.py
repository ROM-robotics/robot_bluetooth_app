#!/usr/bin/env python3
"""
Bluetooth RFCOMM Server CLI - No Passkey Required
Automatically accepts pairing requests using NoInputNoOutput capability.
"""

import socket
import subprocess
import sys
import signal
import threading
import argparse

# D-Bus imports for auto-pairing agent
try:
    import dbus
    import dbus.service
    import dbus.mainloop.glib
    from gi.repository import GLib
    DBUS_AVAILABLE = True
except ImportError:
    DBUS_AVAILABLE = False
    print("Warning: D-Bus/GLib not available. Auto-pairing may not work.")


class NoPinPairingAgent(dbus.service.Object):
    """
    BlueZ D-Bus agent that auto-accepts all pairing requests without PIN.
    Uses 'NoInputNoOutput' capability for seamless pairing.
    """

    AGENT_INTERFACE = "org.bluez.Agent1"

    def __init__(self, bus, path, server=None):
        super().__init__(bus, path)
        self.server = server
        print("  ✓ No-PIN pairing agent created")

    @dbus.service.method(AGENT_INTERFACE, in_signature="", out_signature="")
    def Release(self):
        print("  Agent released")

    @dbus.service.method(AGENT_INTERFACE, in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid):
        print(f"  → Auto-authorizing service for {device}")

    @dbus.service.method(AGENT_INTERFACE, in_signature="o", out_signature="s")
    def RequestPinCode(self, device):
        print(f"  → Returning empty PIN for {device}")
        return ""

    @dbus.service.method(AGENT_INTERFACE, in_signature="o", out_signature="u")
    def RequestPasskey(self, device):
        print(f"  → Returning passkey 0 for {device}")
        return dbus.UInt32(0)

    @dbus.service.method(AGENT_INTERFACE, in_signature="ouq", out_signature="")
    def DisplayPasskey(self, device, passkey, entered):
        print(f"  → Display passkey {passkey:06d} for {device}")

    @dbus.service.method(AGENT_INTERFACE, in_signature="os", out_signature="")
    def DisplayPinCode(self, device, pincode):
        print(f"  → Display PIN {pincode} for {device}")

    @dbus.service.method(AGENT_INTERFACE, in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey):
        print(f"  → Auto-confirming passkey {passkey:06d} for {device}")

    @dbus.service.method(AGENT_INTERFACE, in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        print(f"  → Auto-authorizing {device}")

    @dbus.service.method(AGENT_INTERFACE, in_signature="", out_signature="")
    def Cancel(self):
        print("  → Pairing cancelled - will remove stale pairing info")
        # When pairing is cancelled (e.g., client forgot device), trigger cleanup
        if self.server:
            self.server.schedule_pairing_cleanup()


class BluetoothServerCLI:
    """Simple Bluetooth RFCOMM server with no-passkey pairing."""

    def __init__(self, channel=1, device_name="ROM_Robot"):
        self.channel = channel
        self.device_name = device_name
        self.server_sock = None
        self.client_sock = None
        self.running = False
        self.agent = None
        self.mainloop = None
        self.mainloop_thread = None
        self.bus = None
        self.connected_device = None
        self.connection_status = "disconnected"
        self.cleanup_pending = False
        
        # Track connection attempts for detecting pairing issues
        self.connection_attempts = {}  # mac -> list of timestamps
        self.last_disconnect_time = {}  # mac -> timestamp
        
    def detect_pairing_issue(self, mac):
        """Detect rapid connect/disconnect pattern indicating pairing mismatch."""
        import time
        now = time.time()
        
        # Initialize tracking for this device
        if mac not in self.connection_attempts:
            self.connection_attempts[mac] = []
        
        # Add current attempt
        self.connection_attempts[mac].append(now)
        
        # Keep only attempts from last 10 seconds
        self.connection_attempts[mac] = [
            t for t in self.connection_attempts[mac] 
            if now - t < 10.0
        ]
        
        # If 3+ rapid connect attempts in 10 seconds, it's a pairing issue
        if len(self.connection_attempts[mac]) >= 3:
            return True
        
        # Also check for rapid disconnect after connect
        if mac in self.last_disconnect_time:
            time_since_disconnect = now - self.last_disconnect_time[mac]
            if time_since_disconnect < 2.0:  # Disconnect within 2 seconds
                return True
        
        return False

    def schedule_pairing_cleanup(self):
        """Schedule cleanup of stale pairing info."""
        self.cleanup_pending = True
        print("  [!] Pairing cleanup scheduled")

    def remove_device(self, mac_address):
        """Remove a specific paired device."""
        try:
            result = subprocess.run(
                ["bluetoothctl", "remove", mac_address],
                capture_output=True,
                text=True,
                timeout=5,
                check=False
            )
            print(f"  ✓ Removed pairing for: {mac_address}")
            # Clear tracking for this device
            if mac_address in self.connection_attempts:
                del self.connection_attempts[mac_address]
            if mac_address in self.last_disconnect_time:
                del self.last_disconnect_time[mac_address]
            return True
        except Exception as e:
            print(f"  ⚠ Failed to remove device {mac_address}: {e}")
            return False

    def on_properties_changed(self, interface, changed, invalidated, path=None):
        """Handle D-Bus property changes for connection status."""
        import time
        
        if interface == "org.bluez.Device1":
            # Extract MAC from path like /org/bluez/hci0/dev_XX_XX_XX_XX_XX_XX
            mac = path.split("/")[-1].replace("dev_", "").replace("_", ":") if path else "unknown"
            
            if "Connected" in changed:
                connected = bool(changed["Connected"])
                
                if connected:
                    self.connection_status = "connected"
                    self.connected_device = mac
                    
                    # Check for pairing issue pattern
                    if self.detect_pairing_issue(mac):
                        print(f"\n  ⚠ PAIRING ISSUE DETECTED: {mac}")
                        print(f"  [!] Rapid connect/disconnect pattern - removing stale pairing...")
                        self.remove_device(mac)
                        print(f"  [!] Device can now pair fresh. Client should retry connection.")
                    else:
                        print(f"\n  ✓ CONNECTED: {mac}")
                else:
                    self.connection_status = "disconnected"
                    self.last_disconnect_time[mac] = time.time()
                    print(f"\n  ✗ DISCONNECTED: {mac}")
                    
                    # If cleanup was pending, do it now
                    if self.cleanup_pending:
                        print("  [!] Running scheduled cleanup...")
                        self.remove_device(mac)
                        self.cleanup_pending = False
                    
                    self.connected_device = None

            if "Paired" in changed:
                paired = bool(changed["Paired"])
                if paired:
                    print(f"\n  ✓ PAIRED: {mac}")
                    # Reset connection attempts on successful pair
                    if mac in self.connection_attempts:
                        self.connection_attempts[mac] = []
                else:
                    print(f"\n  ✗ UNPAIRED: {mac}")

    def on_interfaces_added(self, path, interfaces):
        """Handle new device discovery."""
        if "org.bluez.Device1" in interfaces:
            props = interfaces["org.bluez.Device1"]
            name = props.get("Name", "Unknown")
            addr = props.get("Address", "??:??:??:??:??:??")
            print(f"\n  [+] Device discovered: {name} ({addr})")

    def on_interfaces_removed(self, path, interfaces):
        """Handle device removal."""
        if "org.bluez.Device1" in interfaces:
            mac = path.split("/")[-1].replace("dev_", "").replace("_", ":")
            print(f"\n  [-] Device removed: {mac}")

    def setup_dbus_listeners(self):
        """Setup D-Bus listeners for connection/disconnection events."""
        if not DBUS_AVAILABLE or not self.bus:
            return False

        try:
            # Listen for property changes (connect/disconnect)
            self.bus.add_signal_receiver(
                self.on_properties_changed,
                dbus_interface="org.freedesktop.DBus.Properties",
                signal_name="PropertiesChanged",
                path_keyword="path"
            )

            # Listen for new devices
            self.bus.add_signal_receiver(
                self.on_interfaces_added,
                dbus_interface="org.freedesktop.DBus.ObjectManager",
                signal_name="InterfacesAdded"
            )

            # Listen for removed devices
            self.bus.add_signal_receiver(
                self.on_interfaces_removed,
                dbus_interface="org.freedesktop.DBus.ObjectManager",
                signal_name="InterfacesRemoved"
            )

            print("  ✓ D-Bus listeners setup for connection tracking")
            return True

        except Exception as e:
            print(f"  ⚠ Could not setup D-Bus listeners: {e}")
            return False

    def setup_no_pin_agent(self):
        """Register D-Bus agent for no-PIN pairing."""
        if not DBUS_AVAILABLE:
            print("  ⚠ D-Bus not available, skipping agent setup")
            return False

        try:
            dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
            self.bus = dbus.SystemBus()

            agent_path = "/org/bluez/NoPinAgent"
            self.agent = NoPinPairingAgent(self.bus, agent_path, server=self)

            # Register with BlueZ
            manager = dbus.Interface(
                self.bus.get_object("org.bluez", "/org/bluez"),
                "org.bluez.AgentManager1"
            )
            manager.RegisterAgent(agent_path, "NoInputNoOutput")
            manager.RequestDefaultAgent(agent_path)

            print("  ✓ Agent registered with NoInputNoOutput capability")

            # Setup connection tracking
            self.setup_dbus_listeners()

            # Run GLib mainloop in background
            def run_loop():
                self.mainloop = GLib.MainLoop()
                self.mainloop.run()

            self.mainloop_thread = threading.Thread(target=run_loop, daemon=True)
            self.mainloop_thread.start()

            return True

        except Exception as e:
            print(f"  ⚠ Agent setup failed: {e}")
            return False

    def configure_adapter(self):
        """Configure Bluetooth adapter for discovery and pairing."""
        print(f"[2/4] Configuring Bluetooth adapter as '{self.device_name}'...")

        # Step 1: Bring adapter up
        subprocess.run(["sudo", "hciconfig", "hci0", "up"], capture_output=True, check=False)

        # Step 2: Use bluetoothctl for all settings (more reliable than hciconfig)
        bt_script = f"""power on
system-alias {self.device_name}
discoverable on
discoverable-timeout 0
pairable on
agent NoInputNoOutput
default-agent
exit
"""
        result = subprocess.run(
            ["bluetoothctl"],
            input=bt_script,
            text=True,
            capture_output=True,
            timeout=10,
            check=False
        )

        # Step 3: Also set via hciconfig as backup
        subprocess.run(["sudo", "hciconfig", "hci0", "piscan"], capture_output=True, check=False)
        subprocess.run(["sudo", "hciconfig", "hci0", "sspmode", "1"], capture_output=True, check=False)
        subprocess.run(["sudo", "hciconfig", "hci0", "name", self.device_name], capture_output=True, check=False)

        # Step 4: Force class of device update
        subprocess.run(["sudo", "hciconfig", "hci0", "class", "0x000100"], capture_output=True, check=False)

        print(f"  ✓ Adapter configured as '{self.device_name}'")
        print("  ✓ Discoverable (no timeout)")
        print("  ✓ Pairable")
        return True

    def remove_all_paired_devices(self):
        """Remove all paired devices to allow fresh pairing."""
        print("[*] Checking for stale paired devices...")
        try:
            # Get list of paired devices
            result = subprocess.run(
                ["bluetoothctl", "paired-devices"],
                capture_output=True,
                text=True,
                timeout=5,
                check=False
            )

            devices = []
            for line in result.stdout.strip().split("\n"):
                if line.startswith("Device "):
                    parts = line.split()
                    if len(parts) >= 2:
                        devices.append(parts[1])  # MAC address

            if devices:
                print(f"  Found {len(devices)} paired device(s), removing...")
                for mac in devices:
                    subprocess.run(
                        ["bluetoothctl", "remove", mac],
                        capture_output=True,
                        text=True,
                        timeout=5,
                        check=False
                    )
                    print(f"    Removed: {mac}")
                print("  ✓ All paired devices removed")
            else:
                print("  ✓ No stale paired devices")

        except Exception as e:
            print(f"  ⚠ Could not check paired devices: {e}")

    def get_adapter_address(self):
        """Get Bluetooth adapter MAC address."""
        try:
            result = subprocess.run(
                ["hciconfig", "hci0"],
                capture_output=True,
                text=True,
                check=False
            )
            for line in result.stdout.split("\n"):
                if "BD Address:" in line:
                    return line.split("BD Address:")[1].split()[0]
        except Exception:
            pass
        return None

    def register_sdp(self):
        """Register Serial Port Profile in SDP."""
        try:
            result = subprocess.run(
                ["sdptool", "add", f"--channel={self.channel}", "SP"],
                capture_output=True,
                text=True,
                check=False
            )
            if result.returncode == 0:
                print("  ✓ SDP Serial Port service registered")
                return True
        except Exception:
            pass

        print("  ⚠ Using automatic BlueZ SDP (sdptool unavailable)")
        return True

    def kill_process_safely(self, process):
        """Safely kill a process and its group."""
        if not process:
            return
        try:
            import os
            import signal as sig
            if process.poll() is None:  # Still running
                os.killpg(os.getpgid(process.pid), sig.SIGKILL)
                process.wait(timeout=1)
        except Exception:
            pass

    def run_nmcli_command(self, cmd_list, timeout=10):
        """Run nmcli command with proper timeout and error handling."""
        import os
        process = None
        try:
            process = subprocess.Popen(
                cmd_list,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                start_new_session=True
            )
            stdout, stderr = process.communicate(timeout=timeout)
            return process.returncode, stdout, stderr
        except subprocess.TimeoutExpired:
            self.kill_process_safely(process)
            return -1, "", "Command timeout"
        except Exception as e:
            self.kill_process_safely(process)
            return -1, "", str(e)

    def handle_command(self, cmd):
        """Process command from client."""
        cmd = cmd.strip()
        cmd_upper = cmd.upper()  # For command matching only, preserve original for parameters

        if cmd_upper == "PING":
            return "PONG"

        if cmd_upper == "SEARCH_WIFI":
            # First check if WiFi is enabled
            returncode, stdout, stderr = self.run_nmcli_command(
                ["nmcli", "radio", "wifi"],
                timeout=3
            )
            if returncode == 0 and "disabled" in stdout.lower():
                # Enable WiFi
                self.run_nmcli_command(["nmcli", "radio", "wifi", "on"], timeout=3)
            
            # Get saved/known connection profiles
            known_ssids = set()
            rc_known, stdout_known, _ = self.run_nmcli_command(
                ["nmcli", "-t", "-f", "NAME,TYPE", "connection", "show"],
                timeout=5
            )
            if rc_known == 0:
                for line in stdout_known.strip().split("\n"):
                    if line:
                        kparts = line.split(":")
                        if len(kparts) >= 2 and "wireless" in kparts[1].lower():
                            known_ssids.add(kparts[0])
            
            # Scan for networks
            returncode, stdout, stderr = self.run_nmcli_command(
                ["nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY", "device", "wifi", "list", "--rescan", "yes"],
                timeout=20
            )
            
            if returncode != 0:
                return f"ERROR:WiFi scan failed - {stderr[:50] if stderr else 'unknown error'}"
            
            networks = []
            seen_ssids = set()
            for line in stdout.strip().split("\n"):
                if line:
                    parts = line.split(":")
                    ssid = parts[0] if parts else ""
                    if ssid and ssid not in seen_ssids:
                        seen_ssids.add(ssid)
                        signal = parts[1] if len(parts) > 1 else "0"
                        security = parts[2] if len(parts) > 2 else ""
                        known = "Y" if ssid in known_ssids else "N"
                        networks.append(f"{ssid}|{signal}|{security}|{known}")
            
            if not networks:
                return "WIFI_LIST:No networks found"
            return "WIFI_LIST:" + ",".join(networks)

        if cmd_upper.startswith("CONNECT_WIFI:"):
            # Use original cmd to preserve SSID case sensitivity
            parts = cmd.split(":", 2)
            ssid = parts[1] if len(parts) > 1 else ""
            password = parts[2] if len(parts) > 2 else ""
            
            if not ssid:
                return "ERROR:SSID required"
            
            print(f"\n  [WiFi] Connecting to: {ssid}")
            
            # Step 1: Ensure WiFi radio is on
            print(f"  [WiFi] Checking radio status...")
            returncode, stdout, stderr = self.run_nmcli_command(
                ["nmcli", "radio", "wifi"],
                timeout=3
            )
            if returncode == 0 and "disabled" in stdout.lower():
                print(f"  [WiFi] Enabling WiFi radio...")
                self.run_nmcli_command(["nmcli", "radio", "wifi", "on"], timeout=3)
            
            # Step 2: Check if network exists in recent scan
            print(f"  [WiFi] Verifying network exists...")
            returncode, stdout, stderr = self.run_nmcli_command(
                ["nmcli", "-t", "-f", "SSID", "device", "wifi", "list"],
                timeout=10
            )
            
            network_found = False
            if returncode == 0:
                for line in stdout.split("\n"):
                    if line.strip() == ssid:
                        network_found = True
                        break
            
            if not network_found:
                print(f"  [WiFi] Network not found in scan, rescanning...")
                # Do a quick rescan if network not found
                self.run_nmcli_command(
                    ["nmcli", "device", "wifi", "rescan"],
                    timeout=8
                )
                # Wait a moment for scan results
                import time
                time.sleep(2)
            
            # Step 3: Check if this is a known/saved network
            is_known_network = False
            rc_known, stdout_known, _ = self.run_nmcli_command(
                ["nmcli", "-t", "-f", "NAME,TYPE", "connection", "show"],
                timeout=5
            )
            if rc_known == 0:
                for line in stdout_known.strip().split("\n"):
                    if line:
                        kparts = line.split(":")
                        if len(kparts) >= 2 and kparts[0] == ssid and "wireless" in kparts[1].lower():
                            is_known_network = True
                            break
            
            # Step 3a: If known network and no new password, try saved profile first
            if is_known_network and not password:
                print(f"  [WiFi] Known network detected, trying saved profile...")
                returncode, stdout, stderr = self.run_nmcli_command(
                    ["nmcli", "connection", "up", ssid],
                    timeout=30
                )
                if returncode == 0:
                    print(f"  [WiFi] ✓ Connected using saved profile!")
                    return "CONNECT_OK"
                else:
                    print(f"  [WiFi] Saved profile failed, will need password")
                    return "CONNECT_FAIL:Saved password no longer works - please enter new password"
            
            # Step 3b: New password provided — delete old profile and reconnect fresh
            if password:
                print(f"  [WiFi] Removing old connection profile if exists...")
                self.run_nmcli_command(
                    ["nmcli", "connection", "delete", ssid],
                    timeout=5
                )
            
            # Step 4: Try to connect
            print(f"  [WiFi] Attempting connection...")
            cmd_list = ["nmcli", "device", "wifi", "connect", ssid]
            if password:
                cmd_list.extend(["password", password])
            
            returncode, stdout, stderr = self.run_nmcli_command(cmd_list, timeout=30)
            
            if returncode == 0:
                print(f"  [WiFi] ✓ Connected successfully!")
                return "CONNECT_OK"
            else:
                # Parse error from stderr or stdout
                error_msg = (stderr or stdout or "Unknown error").strip()
                print(f"  [WiFi] ✗ Connection failed: {error_msg}")
                
                # Provide clear error messages
                if "timeout" in error_msg.lower():
                    return "CONNECT_FAIL:Timeout - network may be out of range or signal too weak"
                elif "not found" in error_msg.lower() or "no network" in error_msg.lower():
                    return f"CONNECT_FAIL:Network not found - check SSID"
                elif "secrets" in error_msg.lower() or "password" in error_msg.lower():
                    if not password:
                        return f"CONNECT_FAIL:Password required"
                    else:
                        return f"CONNECT_FAIL:Incorrect password"
                elif "no suitable device" in error_msg.lower():
                    return "CONNECT_FAIL:No WiFi adapter found"
                else:
                    # Return first line of error
                    first_error = error_msg.split('\n')[0][:100]
                    return f"CONNECT_FAIL:{first_error}"

        if cmd_upper == "CURRENT_WIFI":
            try:
                result = subprocess.run(
                    ["nmcli", "-t", "-f", "ACTIVE,SSID", "dev", "wifi"],
                    capture_output=True, text=True, check=False
                )
                for line in result.stdout.strip().split("\n"):
                    if line.startswith("yes:"):
                        return f"CURRENT_WIFI:{line.split(':', 1)[1]}"
                return "CURRENT_WIFI:NOT_CONNECTED"
            except Exception as e:
                return f"ERROR:{e}"

        return f"ERROR:Unknown command '{cmd}'"

    def handle_client(self, client_sock, client_info):
        """Handle a connected client."""
        # Extract MAC address from client_info tuple
        client_mac = client_info[0] if client_info else "unknown"
        print(f"\n{'='*50}")
        print(f"  CLIENT CONNECTED: {client_mac}")
        print(f"{'='*50}")
        
        self.client_sock = client_sock
        self.connection_status = "connected"
        self.connected_device = client_mac
        
        try:
            while self.running:
                data = client_sock.recv(1024)
                if not data:
                    break
                cmd = data.decode("utf-8", errors="replace")
                print(f"  ← Received: {cmd.strip()}")
                response = self.handle_command(cmd)
                print(f"  → Sending: {response}")
                client_sock.send((response + "\n").encode("utf-8"))
        except ConnectionResetError:
            print("  ⚠ Connection reset by client")
        except Exception as e:
            print(f"  ⚠ Client error: {e}")
        finally:
            client_sock.close()
            self.client_sock = None
            self.connection_status = "disconnected"
            print(f"\n{'='*50}")
            print(f"  CLIENT DISCONNECTED: {client_mac}")
            print(f"{'='*50}")
            print("  Waiting for new connection...")
            
            # Check if we need to cleanup pairing
            if self.cleanup_pending:
                print("  [!] Cleaning up stale pairing...")
                self.remove_device(client_mac)
                self.cleanup_pending = False

    def start(self):
        """Start the Bluetooth RFCOMM server."""
        print("=" * 50)
        print("  Bluetooth RFCOMM Server (No Passkey Required)")
        print("=" * 50)
        print()

        # Step 0: Remove stale paired devices (fixes "forget then reconnect" issue)
        self.remove_all_paired_devices()
        print()

        # Step 1: Setup no-PIN agent
        print("[1/4] Setting up no-PIN pairing agent...")
        self.setup_no_pin_agent()

        # Step 2: Configure adapter
        self.configure_adapter()

        # Step 3: Create RFCOMM socket
        print("[3/4] Creating RFCOMM socket...")
        try:
            self.server_sock = socket.socket(
                socket.AF_BLUETOOTH,
                socket.SOCK_STREAM,
                socket.BTPROTO_RFCOMM
            )
            self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

            # Set low security (no encryption required)
            try:
                self.server_sock.setsockopt(socket.SOL_BLUETOOTH, 4, 1)
                print("  ✓ Security level set to LOW")
            except Exception:
                print("  ⚠ Could not set security level")

            addr = self.get_adapter_address() or ""
            self.server_sock.bind((addr, self.channel))
            self.server_sock.listen(1)

            print(f"  ✓ Listening on channel {self.channel}")

        except Exception as e:
            print(f"  ✗ Failed to create socket: {e}")
            print("\nTroubleshooting:")
            print("  1. sudo hciconfig hci0 up")
            print("  2. sudo systemctl restart bluetooth")
            print("  3. Run this script with sudo")
            return False

        # Step 4: Register SDP
        print("[4/4] Registering SDP service...")
        self.register_sdp()

        # Ready
        print()
        print("=" * 50)
        addr = self.get_adapter_address() or "unknown"
        print(f"  Server ready: {addr} channel {self.channel}")
        print(f"  Device name: {self.device_name}")
        print("  Pairing: No passkey required")
        print()
        print("  Connection status will be shown in real-time.")
        print("  If client forgets device, server auto-clears")
        print("  pairing info for fresh reconnection.")
        print()
        print("  Press Ctrl+C to stop")
        print("=" * 50)
        print()
        print("  Waiting for connection...")

        self.running = True

        try:
            while self.running:
                try:
                    # Check for pending cleanup before accepting new connection
                    if self.cleanup_pending:
                        print("\n  [!] Performing cleanup before accepting connection...")
                        self.remove_all_paired_devices()
                        self.cleanup_pending = False
                    
                    # Set a timeout so we can periodically check for cleanup
                    self.server_sock.settimeout(5.0)
                    try:
                        client_sock, client_info = self.server_sock.accept()
                        self.handle_client(client_sock, client_info)
                    except socket.timeout:
                        # Timeout is normal, just continue the loop
                        continue
                except OSError as e:
                    if self.running:
                        # Connection failed - might be stale pairing
                        print(f"\n  ⚠ Connection error: {e}")
                        print("  [!] Clearing all paired devices to allow fresh pairing...")
                        self.remove_all_paired_devices()
                    break
        except KeyboardInterrupt:
            print("\n\nShutting down...")
        finally:
            self.stop()

        return True

    def stop(self):
        """Stop the server."""
        self.running = False
        if self.mainloop:
            self.mainloop.quit()
        if self.client_sock:
            try:
                self.client_sock.close()
            except Exception:
                pass
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass
        print("Server stopped.")


def main():
    parser = argparse.ArgumentParser(
        description="Bluetooth RFCOMM Server - No Passkey Required"
    )
    parser.add_argument(
        "-c", "--channel",
        type=int,
        default=1,
        help="RFCOMM channel (default: 1)"
    )
    parser.add_argument(
        "-n", "--name",
        type=str,
        default="ROM_Robot",
        help="Bluetooth device name (default: ROM_Robot)"
    )
    parser.add_argument(
        "--clear-paired",
        action="store_true",
        help="Remove all paired devices before starting"
    )
    args = parser.parse_args()

    server = BluetoothServerCLI(channel=args.channel, device_name=args.name)

    # Handle signals gracefully
    def signal_handler(sig, frame):
        print("\nReceived signal, stopping...")
        server.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    success = server.start()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
