# WireKey (AutoTyper)

WireKey (hosted as AutoTyper) is an Android application that turns your smartphone into a fully functional Bluetooth HID keyboard. It operates seamlessly without requiring any custom client software on the host computer.

## Features
- **Plug and Play**: No host software needed. Works out of the box with standard Bluetooth.
- **Cross-Platform**: Supports Windows, macOS, Linux, iPadOS, and ChromeOS.
- **Low Latency**: Utilizes Android's native `BluetoothHidDevice` API for immediate typing response.

## Requirements
- **Minimum Android Version**: Android 9 (API 28). This is required because Android introduced native APIs for hosting `BluetoothHidDevice` profiles (the foundation of WireKey's functionality) in API 28.
- **Host Device**: Any computer or tablet that supports standard Bluetooth keyboard pairing.

## Permissions Explained
WireKey requires several permissions to function securely and reliably:
- `BLUETOOTH_CONNECT`: Allows the app to connect with already-paired Bluetooth devices.
- `BLUETOOTH_SCAN`: Allows the app to look for nearby Bluetooth devices (without tracking location).
- `BLUETOOTH_ADVERTISE`: Allows the phone to broadcast itself as a discoverable keyboard so your PC can find it during the initial pairing process.
- (Legacy) `ACCESS_FINE_LOCATION`: Required on Android 11 and below to perform Bluetooth discovery.

## How to Pair
1. Open the WireKey app and grant the requested permissions.
2. Ensure your phone's Bluetooth is turned on.
3. Tap **Make this phone discoverable** in WireKey. Your phone is now broadcasting its availability.
4. On your PC, open Bluetooth Settings and select **Add Device**.
5. Look for your phone's name (e.g., "Pixel 7 Pro") in the PC's Bluetooth list and pair it.
6. Once paired, the PC will appear in WireKey's bonded devices list.
7. Tap the device in WireKey to connect. You will automatically drop into Live Mode!

## Known Limitations
- **Background Restrictions**: Some custom Android skins (OEM implementations) heavily restrict background Bluetooth connections. If the app goes to sleep, the HID connection may drop. WireKey includes auto-reconnection logic, but if the OS kills the process entirely, you will need to re-open the app.
- **Development Re-pairing**: If you are a developer modifying the HID descriptor in the source code, the host PC may cache the old descriptor. You must "Remove Device" on your PC and re-pair from scratch for the new layout to take effect.

