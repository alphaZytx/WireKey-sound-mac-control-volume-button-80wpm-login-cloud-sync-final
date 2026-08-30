import AppKit
import CoreBluetooth
import CoreGraphics
import Foundation
import IOKit.hid

private enum WireKeyBleProtocol {
    static var service: CBUUID { CBUUID(string: "8D09A2C2-744F-4F17-B34F-0271D1B9A3D1") }
    static var commandCharacteristic: CBUUID { CBUUID(string: "8D09A2C3-744F-4F17-B34F-0271D1B9A3D1") }
    static let trigger = Data([0x01, 0x01]) // protocol version 1, trigger command 1
}

/** Connects to the WireKey phone and serializes short trigger writes. */
private final class WireKeyCentral: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private var central: CBCentralManager!
    private var phone: CBPeripheral?
    private var commandCharacteristic: CBCharacteristic?
    private var queuedTriggers = 0
    private var writeInFlight = false

    private let savedPhoneIdentifierKey = "wirekey.remote.phone.identifier"
    private let retryDelay: TimeInterval = 1
    private let connectTimeout: TimeInterval = 8
    private var connectAttempt = 0

    var onStatus: (String) -> Void = { _ in }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func sendTrigger() {
        // A Caps double-tap produces exactly one command, but retaining a small queue lets
        // a deliberate command made while the phone is reconnecting complete once ready.
        queuedTriggers = min(queuedTriggers + 1, 3)
        sendNextTriggerIfReady()
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            reconnectOrScan()
        case .poweredOff:
            onStatus("Bluetooth is off")
        case .unauthorized:
            onStatus("Bluetooth permission is not allowed")
        case .unsupported:
            onStatus("Bluetooth LE is unavailable on this Mac")
        case .resetting:
            onStatus("Bluetooth is resetting")
        case .unknown:
            onStatus("Checking Bluetooth…")
        @unknown default:
            onStatus("Unknown Bluetooth state")
        }
    }

    private func reconnectOrScan() {
        guard phone == nil else { return }
        if let identifierText = UserDefaults.standard.string(forKey: savedPhoneIdentifierKey),
           let identifier = UUID(uuidString: identifierText),
           let knownPhone = central.retrievePeripherals(withIdentifiers: [identifier]).first {
            connect(to: knownPhone, status: "Reconnecting to WireKey…")
            return
        }

        if let connectedPhone = central.retrieveConnectedPeripherals(
            withServices: [WireKeyBleProtocol.service]
        ).first {
            connect(to: connectedPhone, status: "Connecting to WireKey…")
            return
        }
        scanForPhone()
    }

    private func scanForPhone() {
        guard central.state == .poweredOn else { return }
        guard phone == nil else { return }
        if !central.isScanning {
            onStatus("Looking for the WireKey phone…")
            central.scanForPeripherals(withServices: [WireKeyBleProtocol.service], options: nil)
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard phone == nil else { return }
        central.stopScan()
        connect(to: peripheral, status: "Connecting to WireKey…")
    }

    private func connect(to peripheral: CBPeripheral, status: String) {
        phone = peripheral // Retain it; CoreBluetooth otherwise may release it before connecting.
        onStatus(status)
        // No connect options: CoreBluetooth rejects CBConnectPeripheralOptionEnableAutoReconnect
        // for this peripheral with CBError.invalidParameters ("One or more parameters were
        // invalid"), which fails the connection instantly. didDisconnectPeripheral already
        // re-scans, so the helper reconnects without needing that option.
        central.connect(peripheral, options: [:])
        scheduleConnectTimeout(for: peripheral)
    }

    /**
     * Abandons a connection that never completes.
     *
     * Android advertises from a rotating private address, so an identifier saved on an earlier
     * run goes stale within minutes. CoreBluetooth leaves a connect to a peripheral it can no
     * longer reach pending indefinitely -- reporting neither success nor failure -- which would
     * strand the helper with `phone` set and `scanForPhone()` blocked by its `phone == nil` guard.
     */
    private func scheduleConnectTimeout(for peripheral: CBPeripheral) {
        connectAttempt += 1
        let attempt = connectAttempt
        DispatchQueue.main.asyncAfter(deadline: .now() + connectTimeout) { [weak self] in
            guard let self,
                  self.connectAttempt == attempt, // A newer attempt supersedes this one.
                  self.phone === peripheral,
                  peripheral.state != .connected
            else { return }

            // Cancelling a still-pending connect does not reliably call a delegate method,
            // so clear the state here instead of waiting to be told.
            self.central.cancelPeripheralConnection(peripheral)
            self.phone = nil
            self.commandCharacteristic = nil
            self.writeInFlight = false
            // A stale saved identifier is the usual cause; the next successful connect
            // writes a fresh one, so dropping it costs nothing.
            UserDefaults.standard.removeObject(forKey: self.savedPhoneIdentifierKey)
            self.onStatus("WireKey did not answer; looking again…")
            self.reconnectOrScan()
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral == phone else { return }
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: savedPhoneIdentifierKey)
        peripheral.delegate = self
        onStatus("Connected — discovering WireKey controls…")
        peripheral.discoverServices([WireKeyBleProtocol.service])
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral == phone else { return }
        phone = nil
        commandCharacteristic = nil
        onStatus("Could not connect to WireKey; retrying…")
        // Pause before retrying: a repeatable connect failure otherwise becomes a
        // scan/connect storm that pins the radio and drains the battery.
        DispatchQueue.main.asyncAfter(deadline: .now() + retryDelay) { [weak self] in
            self?.reconnectOrScan()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral == phone else { return }
        phone = nil
        commandCharacteristic = nil
        writeInFlight = false
        onStatus("WireKey disconnected; waiting to reconnect…")
        reconnectOrScan()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == WireKeyBleProtocol.service })
        else {
            reconnectAfterDiscoveryFailure("WireKey's control service was not found")
            return
        }
        peripheral.discoverCharacteristics([WireKeyBleProtocol.commandCharacteristic], for: service)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard error == nil,
              let characteristic = service.characteristics?.first(where: {
                  $0.uuid == WireKeyBleProtocol.commandCharacteristic && $0.properties.contains(.write)
              })
        else {
            reconnectAfterDiscoveryFailure("WireKey's command control was not found")
            return
        }

        commandCharacteristic = characteristic
        onStatus("Ready — press Caps Lock twice to control WireKey")
        sendNextTriggerIfReady()
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        writeInFlight = false
        if let error {
            onStatus("WireKey did not accept the command: \(error.localizedDescription)")
        } else {
            onStatus("Command sent to WireKey")
            queuedTriggers = max(0, queuedTriggers - 1)
        }
        sendNextTriggerIfReady()
    }

    private func sendNextTriggerIfReady() {
        guard queuedTriggers > 0,
              !writeInFlight,
              let phone,
              let commandCharacteristic
        else { return }

        writeInFlight = true
        phone.writeValue(WireKeyBleProtocol.trigger, for: commandCharacteristic, type: .withResponse)
    }

    private func reconnectAfterDiscoveryFailure(_ message: String) {
        onStatus("\(message); retrying…")
        if let phone {
            central.cancelPeripheralConnection(phone)
        } else {
            reconnectOrScan()
        }
    }
}

/**
 * Passively watches the physical Caps Lock key without swallowing or changing it.
 *
 * The raw IOHID callback receives the actual down edge before macOS finishes its intentional
 * Caps Lock activation delay. That makes an ordinary quick double press responsive. The
 * CGEvent tap is retained only as a fallback for Macs that deny raw HID access.
 */
private final class CapsLockMonitor {
    private var hidManager: IOHIDManager?
    private var fallbackEventTap: CFMachPort?
    private var fallbackRunLoopSource: CFRunLoopSource?
    private var fallbackLastCapsState: Bool?
    private var lastTapAt: TimeInterval?

    var onDoubleTap: () -> Void = {}
    var onStatus: (String) -> Void = { _ in }

    func start() {
        stop()

        guard CGPreflightListenEventAccess() else {
            onStatus("Allow Input Monitoring, then choose Retry Input Monitoring")
            return
        }

        let manager = IOHIDManagerCreate(kCFAllocatorDefault, IOOptionBits(kIOHIDOptionsTypeNone))
        let keyboardMatch: CFDictionary = [
            kIOHIDDeviceUsagePageKey: kHIDPage_GenericDesktop,
            kIOHIDDeviceUsageKey: kHIDUsage_GD_Keyboard
        ] as CFDictionary
        IOHIDManagerSetDeviceMatching(manager, keyboardMatch)
        IOHIDManagerRegisterInputValueCallback(manager, CapsLockMonitor.inputValueCallback, Unmanaged.passUnretained(self).toOpaque())
        IOHIDManagerScheduleWithRunLoop(manager, CFRunLoopGetMain(), CFRunLoopMode.commonModes.rawValue)

        if IOHIDManagerOpen(manager, IOOptionBits(kIOHIDOptionsTypeNone)) == kIOReturnSuccess {
            hidManager = manager
            onStatus("Fast Caps Lock listener is ready")
            return
        }

        IOHIDManagerUnscheduleFromRunLoop(manager, CFRunLoopGetMain(), CFRunLoopMode.commonModes.rawValue)
        startEventTapFallback()
    }

    private func startEventTapFallback() {
        let mask = CGEventMask(1 << CGEventType.flagsChanged.rawValue)
        let pointer = Unmanaged.passUnretained(self).toOpaque()
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .listenOnly,
            eventsOfInterest: mask,
            callback: CapsLockMonitor.eventTapCallback,
            userInfo: pointer
        ) else {
            onStatus("Could not listen for Caps Lock — check Input Monitoring")
            return
        }

        let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        fallbackEventTap = tap
        fallbackRunLoopSource = source
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
        onStatus("Caps Lock listener is ready (fallback mode)")
    }

    func requestPermissionAndRetry() {
        _ = CGRequestListenEventAccess()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.start()
        }
    }

    func stop() {
        if let manager = hidManager {
            IOHIDManagerUnscheduleFromRunLoop(manager, CFRunLoopGetMain(), CFRunLoopMode.commonModes.rawValue)
            IOHIDManagerClose(manager, IOOptionBits(kIOHIDOptionsTypeNone))
        }
        hidManager = nil

        if let source = fallbackRunLoopSource {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes)
        }
        fallbackEventTap = nil
        fallbackRunLoopSource = nil
        fallbackLastCapsState = nil
        lastTapAt = nil
    }

    private func handleRawCapsLockValue(_ value: IOHIDValue) {
        let element = IOHIDValueGetElement(value)
        guard IOHIDElementGetUsagePage(element) == kHIDPage_KeyboardOrKeypad,
              IOHIDElementGetUsage(element) == kHIDUsage_KeyboardCapsLock,
              IOHIDValueGetIntegerValue(value) != 0
        else { return }
        recordPress()
    }

    private func handle(eventType: CGEventType, event: CGEvent) {
        if eventType == .tapDisabledByTimeout || eventType == .tapDisabledByUserInput {
            if let fallbackEventTap {
                CGEvent.tapEnable(tap: fallbackEventTap, enable: true)
            }
            return
        }

        guard eventType == .flagsChanged,
              event.getIntegerValueField(.keyboardEventKeycode) == 57 // kVK_CapsLock
        else { return }

        let capsOn = event.flags.contains(.maskAlphaShift)
        guard fallbackLastCapsState != capsOn else { return }
        fallbackLastCapsState = capsOn

        recordPress()
    }

    private func recordPress() {
        let now = Date.timeIntervalSinceReferenceDate
        if let previous = lastTapAt, now - previous <= doubleTapWindow {
            // Forget the completed pair so a third press starts a fresh pair rather than
            // being treated as the second half of another command.
            lastTapAt = nil
            onDoubleTap()
        } else {
            lastTapAt = now
        }
    }

    private static let inputValueCallback: IOHIDValueCallback = { context, _, _, value in
        guard let context else { return }
        Unmanaged<CapsLockMonitor>.fromOpaque(context).takeUnretainedValue().handleRawCapsLockValue(value)
    }

    private static let eventTapCallback: CGEventTapCallBack = { _, type, event, userInfo in
        guard let userInfo else { return nil }
        Unmanaged<CapsLockMonitor>.fromOpaque(userInfo).takeUnretainedValue().handle(
            eventType: type,
            event: event
        )
        return Unmanaged.passUnretained(event)
    }

    private let doubleTapWindow: TimeInterval = 0.75
}

private final class AppDelegate: NSObject, NSApplicationDelegate {
    private let central = WireKeyCentral()
    private let capsLock = CapsLockMonitor()
    private var statusItem: NSStatusItem!
    // The two subsystems report independently: a working Bluetooth link says nothing about
    // whether Caps Lock is actually being heard, and sharing one line let whichever spoke
    // last hide the other's failure.
    private var connectionLine: NSMenuItem!
    private var capsLockLine: NSMenuItem!
    private var connectionStatus = "Starting…"
    private var capsLockStatus = "Starting…"

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        installMenu()

        central.onStatus = { [weak self] status in self?.setConnectionStatus(status) }
        capsLock.onStatus = { [weak self] status in self?.setCapsLockStatus(status) }
        capsLock.onDoubleTap = { [weak self] in
            self?.setCapsLockStatus("double-tap detected — sending…")
            self?.central.sendTrigger()
        }

        capsLock.requestPermissionAndRetry()
    }

    private func installMenu() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.title = "WK"
        statusItem.button?.toolTip = "WireKey Remote"

        let menu = NSMenu()
        connectionLine = NSMenuItem(title: "Phone: starting…", action: nil, keyEquivalent: "")
        connectionLine.isEnabled = false
        menu.addItem(connectionLine)
        capsLockLine = NSMenuItem(title: "Caps Lock: starting…", action: nil, keyEquivalent: "")
        capsLockLine.isEnabled = false
        menu.addItem(capsLockLine)
        menu.addItem(.separator())
        menu.addItem(withTitle: "Retry Input Monitoring", action: #selector(retryInputMonitoring), keyEquivalent: "")
        menu.addItem(withTitle: "Quit WireKey Remote", action: #selector(quit), keyEquivalent: "q")
        statusItem.menu = menu
    }

    private func setConnectionStatus(_ message: String) {
        connectionStatus = message
        connectionLine?.title = "Phone: \(message)"
        refreshTooltip()
    }

    private func setCapsLockStatus(_ message: String) {
        capsLockStatus = message
        capsLockLine?.title = "Caps Lock: \(message)"
        refreshTooltip()
    }

    private func refreshTooltip() {
        statusItem.button?.toolTip = "WireKey Remote\nPhone: \(connectionStatus)\nCaps Lock: \(capsLockStatus)"
    }

    @objc private func retryInputMonitoring() {
        capsLock.requestPermissionAndRetry()
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }
}

@main
private struct WireKeyRemoteApp {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.run()
    }
}
