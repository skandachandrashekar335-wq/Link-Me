@preconcurrency import CoreBluetooth
import Foundation

/// Splits serialized wire frames across negotiated BLE ATT MTU - 3 boundaries.
public enum BleFrameChunker {
    public static func chunk(frameData: Data, mtu: Int = 23) -> [Data] {
        let maxChunk = max(1, mtu - 3)
        guard !frameData.isEmpty else { return [] }
        var chunks: [Data] = []
        var offset = frameData.startIndex
        while offset < frameData.endIndex {
            let end = frameData.index(offset, offsetBy: maxChunk, limitedBy: frameData.endIndex) ?? frameData.endIndex
            chunks.append(Data(frameData[offset..<end]))
            offset = end
        }
        return chunks
    }
}

/// Low-power BLE Central scanning strictly for Service UUID 414D0001-0000-1000-8000-00805F9B34FB ("AM01").
/// Validates rotating 8-byte BlePrivacyBeacon tokens against TrustStore before connecting.
public final class BleCentralManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    // ASCII "AM" = 0x414D (valid RFC 4122 hex representing "0000AM01/02/03")
    public static let serviceUUID = CBUUID(string: "414D0001-0000-1000-8000-00805F9B34FB")
    public static let controlWriteCharUUID = CBUUID(string: "414D0002-0000-1000-8000-00805F9B34FB")
    public static let controlNotifyCharUUID = CBUUID(string: "414D0003-0000-1000-8000-00805F9B34FB")

    private let trustStore: TrustStore
    private let queue = DispatchQueue(label: "com.linkme.macbridge.ble.central")
    private var centralManager: CBCentralManager?
    private var connectedPeripheral: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var frameDecoder = FrameDecoder()
    private let replayGuard = ReplayGuard()

    public var onVerifiedPeerDiscovered: (@Sendable (TrustedPeer) -> Void)?
    public var onInboundFrame: (@Sendable (WireFrame) -> Void)?

    public init(trustStore: TrustStore) {
        self.trustStore = trustStore
        super.init()
    }

    public func startScanning() {
        queue.async { [weak self] in
            guard let self else { return }
            if self.centralManager == nil {
                self.centralManager = CBCentralManager(delegate: self, queue: self.queue)
            } else if self.centralManager?.state == .poweredOn {
                self.beginFilteredScan()
            }
        }
    }

    public func stopScanning() {
        queue.async { [weak self] in
            self?.centralManager?.stopScan()
        }
    }

    public func sendControlFrame(_ frame: WireFrame) {
        queue.async { [weak self] in
            guard let self,
                  let peripheral = self.connectedPeripheral,
                  let char = self.writeCharacteristic else {
                return
            }
            let raw = FrameEncoder.encode(frame)
            let maxLen = peripheral.maximumWriteValueLength(for: .withResponse)
            let mtu = max(23, maxLen + 3)
            for chunk in BleFrameChunker.chunk(frameData: raw, mtu: mtu) {
                peripheral.writeValue(chunk, for: char, type: .withResponse)
            }
        }
    }

    private func beginFilteredScan() {
        centralManager?.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            beginFilteredScan()
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
              let token = serviceData[Self.serviceUUID],
              token.count == 8,
              let peers = try? trustStore.allPeers() else {
            return
        }

        guard let matchedPeer = peers.first(where: {
            BlePrivacyBeacon(sharedBleSecret: $0.sharedBleSecret).verify(token: token)
        }) else {
            return // Ignore unrecognized devices immediately without connecting
        }

        onVerifiedPeerDiscovered?(matchedPeer)
        connectedPeripheral = peripheral
        peripheral.delegate = self
        central.stopScan() // Stop scanning while connected to save radio power
        central.connect(peripheral, options: nil)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([Self.serviceUUID])
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        if connectedPeripheral?.identifier == peripheral.identifier {
            connectedPeripheral = nil
            writeCharacteristic = nil
            frameDecoder.reset()
        }
        if central.state == .poweredOn {
            beginFilteredScan()
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else { return }
        peripheral.discoverCharacteristics([Self.controlWriteCharUUID, Self.controlNotifyCharUUID], for: service)
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        for char in service.characteristics ?? [] {
            if char.uuid == Self.controlWriteCharUUID {
                writeCharacteristic = char
            } else if char.uuid == Self.controlNotifyCharUUID {
                peripheral.setNotifyValue(true, for: char)
            }
        }
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard characteristic.uuid == Self.controlNotifyCharUUID,
              let chunk = characteristic.value else { return }
        frameDecoder.append(chunk)
        guard let frames = try? frameDecoder.drainFrames() else {
            frameDecoder.reset()
            return
        }
        for frame in frames {
            Task { [replayGuard, onInboundFrame] in
                if (try? await replayGuard.validateAndAccept(frame.sequenceId)) != nil {
                    onInboundFrame?(frame)
                }
            }
        }
    }
}
