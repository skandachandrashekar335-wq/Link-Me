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
/// Supports E2EE ChaCha20-Poly1305 packetized transport, L2CAP CoC upgrade, state restoration,
/// auto-reconnect, and a 5-second RTT latency heartbeat.
public final class BleCentralManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, StreamDelegate, @unchecked Sendable {
    public static let serviceUUID = CBUUID(string: "414D0001-0000-1000-8000-00805F9B34FB")
    public static let controlWriteCharUUID = CBUUID(string: "414D0002-0000-1000-8000-00805F9B34FB")
    public static let controlNotifyCharUUID = CBUUID(string: "414D0003-0000-1000-8000-00805F9B34FB")
    public static let l2capPsmCharUUID = CBUUID(string: "414D0004-0000-1000-8000-00805F9B34FB")

    private let trustStore: TrustStore
    private let queue = DispatchQueue(label: "com.linkme.macbridge.ble.central")
    private var centralManager: CBCentralManager?
    private var connectedPeripheral: CBPeripheral?
    private var activePeer: TrustedPeer?
    private var writeCharacteristic: CBCharacteristic?
    private var frameDecoder = FrameDecoder()
    private let reassembler = BluetoothPacketReassembler()
    private let l2capFramer = BluetoothL2CapStreamFramer()
    private var activeL2CapChannel: CBL2CAPChannel?
    private let replayGuard = ReplayGuard()
    private var packetSeq: UInt32 = 1
    private var ackWaiters: [UInt32: DispatchSemaphore] = [:]
    private let ackLock = NSLock()
    private var heartbeatTimer: DispatchSourceTimer?
    private var lastPingSentAt: Date?

    public private(set) var isConnected: Bool = false
    public var onVerifiedPeerDiscovered: (@Sendable (TrustedPeer) -> Void)?
    public var onConnectionStateChanged: (@Sendable (Bool) -> Void)?
    public var onLatencyMeasured: (@Sendable (Int) -> Void)?
    public var onInboundFrame: (@Sendable (WireFrame) -> Void)?

    public init(trustStore: TrustStore) {
        self.trustStore = trustStore
        super.init()
    }

    public func startScanning() {
        queue.async { [weak self] in
            guard let self else { return }
            if self.centralManager == nil {
                self.centralManager = CBCentralManager(
                    delegate: self,
                    queue: self.queue,
                    options: [CBCentralManagerOptionRestoreIdentifierKey: "com.linkme.macbridge.central"]
                )
            } else if self.centralManager?.state == .poweredOn {
                self.beginFilteredScan()
            }
        }
    }

    public func stopScanning() {
        queue.async { [weak self] in
            self?.heartbeatTimer?.cancel()
            self?.heartbeatTimer = nil
            self?.activeL2CapChannel?.inputStream?.close()
            self?.activeL2CapChannel?.outputStream?.close()
            self?.activeL2CapChannel = nil
            self?.l2capFramer.reset()
            self?.centralManager?.stopScan()
        }
    }

    public func sendControlFrame(_ frame: WireFrame) {
        queue.async { [weak self] in
            guard let self else { return }

            let isHandshake = frame.messageType == .pairHello || frame.messageType == .pairCommit || frame.messageType == .pairAck
            let envelope: Data
            if !isHandshake,
               let peer = self.activePeer,
               let cipher = try? BluetoothSessionCipher(sessionKey: peer.bluetoothSessionKey, senderTag: "MAC0"),
               let sealed = try? cipher.seal(frame: frame) {
                envelope = sealed
            } else {
                envelope = FrameEncoder.encode(frame)
            }

            if let outStream = self.activeL2CapChannel?.outputStream,
               BluetoothL2CapStreamFramer.writeFramed(envelope, to: outStream) {
                return
            }

            guard let peripheral = self.connectedPeripheral,
                  let char = self.writeCharacteristic else {
                return
            }

            let packets = BluetoothPacketCodec.packetize(envelope: envelope, packetId: self.packetSeq, mtu: min(512, max(23, peripheral.maximumWriteValueLength(for: .withoutResponse) + 3)))
            let pid = self.packetSeq
            self.packetSeq &+= 1
            let writeType: CBCharacteristicWriteType = (packets.count <= 2 && char.properties.contains(.writeWithoutResponse))
                ? .withoutResponse
                : .withResponse

            let sem: DispatchSemaphore? = packets.count > 1 ? DispatchSemaphore(value: 0) : nil
            if let sem {
                self.ackLock.withLock { self.ackWaiters[pid] = sem }
            }
            for pkt in packets {
                peripheral.writeValue(pkt, for: char, type: writeType)
            }
            if let sem {
                _ = sem.wait(timeout: .now() + .milliseconds(800))
                self.ackLock.withLock { _ = self.ackWaiters.removeValue(forKey: pid) }
            }
        }
    }

    private func beginFilteredScan() {
        centralManager?.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    private func startHeartbeat() {
        heartbeatTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 5.0, repeating: 5.0)
        timer.setEventHandler { [weak self] in
            guard let self, self.isConnected else { return }
            let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
            self.lastPingSentAt = Date()
            let cmd = DeviceCommandPayload(command: "btPing", timestampMs: nowMs)
            if let data = try? JSONEncoder().encode(cmd),
               let frame = try? WireFrame(messageType: .deviceCommand, sequenceId: nowMs, payload: data) {
                self.sendControlFrame(frame)
            }
        }
        timer.resume()
        heartbeatTimer = timer
    }

    public func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
           let restored = peripherals.first {
            connectedPeripheral = restored
            restored.delegate = self
            activePeer = (try? trustStore.allPeers())?.first
        }
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
        guard let peers = try? trustStore.allPeers(), !peers.isEmpty else { return }
        let matchedPeer: TrustedPeer?
        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
           let token = serviceData[Self.serviceUUID],
           token.count == 8 {
            matchedPeer = peers.first(where: {
                BlePrivacyBeacon(sharedBleSecret: $0.sharedBleSecret).verify(token: token)
            })
        } else {
            matchedPeer = nil
        }

        guard let matchedPeer else { return }
        activePeer = matchedPeer
        onVerifiedPeerDiscovered?(matchedPeer)
        connectedPeripheral = peripheral
        peripheral.delegate = self
        central.stopScan()
        central.connect(peripheral, options: nil)
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        isConnected = true
        onConnectionStateChanged?(true)
        peripheral.discoverServices([Self.serviceUUID])
        startHeartbeat()
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        if connectedPeripheral?.identifier == peripheral.identifier {
            connectedPeripheral = nil
            writeCharacteristic = nil
            isConnected = false
            onConnectionStateChanged?(false)
            frameDecoder.reset()
            reassembler.reset()
            heartbeatTimer?.cancel()
            heartbeatTimer = nil
        }
        if central.state == .poweredOn {
            beginFilteredScan()
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else { return }
        peripheral.discoverCharacteristics(
            [Self.controlWriteCharUUID, Self.controlNotifyCharUUID, Self.l2capPsmCharUUID],
            for: service
        )
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
            } else if char.uuid == Self.l2capPsmCharUUID {
                peripheral.readValue(for: char)
            }
        }
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if characteristic.uuid == Self.l2capPsmCharUUID,
           let value = characteristic.value,
           value.count == 2 {
            let psm = (UInt16(value[value.startIndex]) << 8) | UInt16(value[value.startIndex + 1])
            if psm > 0 {
                peripheral.openL2CAPChannel(CBL2CAPPSM(psm))
            }
            return
        }

        guard characteristic.uuid == Self.controlNotifyCharUUID,
              let chunk = characteristic.value else { return }

        if chunk.first == BluetoothPacketCodec.ackTag {
            if let ack = BluetoothPacketCodec.AckPacket.decode(chunk) {
                ackLock.withLock { ackWaiters[ack.packetId]?.signal() }
            }
            return
        }

        if chunk.first == BluetoothPacketCodec.dataTag {
            let (_, assembled, _) = reassembler.ingest(chunk)
            guard let assembled else { return }
            decodeAndDeliver(assembled)
            return
        }

        frameDecoder.append(chunk)
        guard let frames = try? frameDecoder.drainFrames() else {
            frameDecoder.reset()
            return
        }
        for frame in frames {
            deliverFrame(frame)
        }
    }

    private func decodeAndDeliver(_ data: Data) {
        if BluetoothSessionCipher.isEncryptedEnvelope(data),
           let peer = activePeer,
           let cipher = try? BluetoothSessionCipher(sessionKey: peer.bluetoothSessionKey),
           let frame = try? cipher.openFrame(from: data) {
            deliverFrame(frame)
        } else if let frame = try? WireFrame.decode(from: data) {
            deliverFrame(frame)
        }
    }

    private func deliverFrame(_ frame: WireFrame) {
        if frame.messageType == .deviceCommand,
           let cmd = try? JSONDecoder().decode(DeviceCommandPayload.self, from: frame.payload),
           cmd.command == "btPong",
           let sent = lastPingSentAt {
            let rttMs = max(1, Int(Date().timeIntervalSince(sent) * 1000))
            onLatencyMeasured?(rttMs)
            return
        }
        Task { [replayGuard, onInboundFrame] in
            if frame.messageType == .pairHello || frame.messageType == .pairCommit || frame.messageType == .pairAck {
                onInboundFrame?(frame)
                return
            }
            if (try? await replayGuard.validateAndAccept(frame.sequenceId)) != nil {
                onInboundFrame?(frame)
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let channel, error == nil else { return }
        activeL2CapChannel = channel
        channel.inputStream?.delegate = self
        channel.inputStream?.schedule(in: .main, forMode: .default)
        channel.inputStream?.open()
        channel.outputStream?.open()
    }

    // MARK: - StreamDelegate (L2CAP CoC Inbound)

    public func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        let shouldHandle = eventCode.contains(.hasBytesAvailable)
        let shouldClose = eventCode.contains(.endEncountered) || eventCode.contains(.errorOccurred)
        guard shouldHandle || shouldClose else { return }
        queue.async { [weak self] in
            guard let self, let inStream = self.activeL2CapChannel?.inputStream else { return }
            if shouldHandle {
                for payload in self.l2capFramer.readAvailable(from: inStream) {
                    self.decodeAndDeliver(payload)
                }
            }
            if shouldClose {
                self.activeL2CapChannel?.inputStream?.close()
                self.activeL2CapChannel?.outputStream?.close()
                self.activeL2CapChannel = nil
                self.l2capFramer.reset()
            }
        }
    }
}
