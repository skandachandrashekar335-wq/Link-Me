@preconcurrency import CoreBluetooth
import Foundation

public struct BlePeripheralDiagnostics: Sendable, Equatable {
    public let isPoweredOn: Bool
    public let isAdvertising: Bool
    public let subscribedCentralsCount: Int
    public let l2capPsm: UInt16?
    public let lastStatusMessage: String

    public init(
        isPoweredOn: Bool = false,
        isAdvertising: Bool = false,
        subscribedCentralsCount: Int = 0,
        l2capPsm: UInt16? = nil,
        lastStatusMessage: String = "Idle"
    ) {
        self.isPoweredOn = isPoweredOn
        self.isAdvertising = isAdvertising
        self.subscribedCentralsCount = subscribedCentralsCount
        self.l2capPsm = l2capPsm
        self.lastStatusMessage = lastStatusMessage
    }
}

/// Hosts MacBridge's BLE GATT Peripheral Service (414D0001-...) & L2CAP CoC channel on launch
/// so paired Android devices can connect and exchange E2EE ChaCha20-Poly1305 frames with ZERO Wi-Fi,
/// and newly scanning Android devices can write initial PAIR_HELLO during QR pairing.
public final class BlePeripheralManager: NSObject, CBPeripheralManagerDelegate, StreamDelegate, @unchecked Sendable {
    public static let serviceUUID = CBUUID(string: "414D0001-0000-1000-8000-00805F9B34FB")
    public static let rxWriteCharUUID = CBUUID(string: "414D0002-0000-1000-8000-00805F9B34FB")
    public static let txNotifyCharUUID = CBUUID(string: "414D0003-0000-1000-8000-00805F9B34FB")
    public static let l2capPsmCharUUID = CBUUID(string: "414D0004-0000-1000-8000-00805F9B34FB")

    private let trustStore: TrustStore
    private let queue = DispatchQueue(label: "com.linkme.macbridge.ble.peripheral")
    private var peripheralManager: CBPeripheralManager?
    private var txNotifyChar: CBMutableCharacteristic?
    private var l2capPsmChar: CBMutableCharacteristic?
    private var serviceAdded = false
    private var advertisedName: String = "MacBridge"

    private let reassembler = BluetoothPacketReassembler()
    private let l2capFramer = BluetoothL2CapStreamFramer()
    private var legacyDecoder = FrameDecoder()
    private let replayGuard = ReplayGuard()
    private var packetSeq: UInt32 = 1
    private var subscribedCentrals: [CBCentral] = []
    private var pendingNotifyQueue: [Data] = []
    private var activeL2CapChannel: CBL2CAPChannel?

    public private(set) var diagnostics = BlePeripheralDiagnostics()
    public var onDiagnosticsChanged: (@Sendable (BlePeripheralDiagnostics) -> Void)?
    public var onInboundFrame: (@Sendable (WireFrame) -> Void)?

    public init(trustStore: TrustStore) {
        self.trustStore = trustStore
        super.init()
    }

    public func start(localName: String = "MacBridge") {
        queue.async { [weak self] in
            guard let self else { return }
            self.advertisedName = localName
            if self.peripheralManager == nil {
                self.peripheralManager = CBPeripheralManager(delegate: self, queue: self.queue)
            } else if self.peripheralManager?.state == .poweredOn {
                self.configureAndAdvertise()
            }
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.peripheralManager?.stopAdvertising()
            self.activeL2CapChannel?.inputStream?.close()
            self.activeL2CapChannel?.outputStream?.close()
            self.activeL2CapChannel = nil
            self.l2capFramer.reset()
            self.updateDiagnostics(isAdvertising: false, message: "Stopped")
        }
    }

    public func sendFrame(_ frame: WireFrame) {
        queue.async { [weak self] in
            guard let self else { return }
            let encoded = self.encodeOutgoingPayload(frame)

            // Prefer open Bluetooth L2CAP CoC stream when available
            if let outStream = self.activeL2CapChannel?.outputStream,
               BluetoothL2CapStreamFramer.writeFramed(encoded, to: outStream) {
                return
            }

            guard let pm = self.peripheralManager,
                  let notifyChar = self.txNotifyChar,
                  !self.subscribedCentrals.isEmpty else {
                return
            }
            let minMtu = self.subscribedCentrals.map { max(23, $0.maximumUpdateValueLength + 3) }.min() ?? 23
            let pid = self.packetSeq
            self.packetSeq &+= 1
            let packets = BluetoothPacketCodec.packetize(envelope: encoded, packetId: pid, mtu: minMtu)
            self.pendingNotifyQueue.append(contentsOf: packets)
            if self.pendingNotifyQueue.count > 2048 {
                self.pendingNotifyQueue.removeFirst(self.pendingNotifyQueue.count - 2048)
            }
            self.drainPendingNotifyQueue(pm: pm, char: notifyChar)
        }
    }

    private func drainPendingNotifyQueue(pm: CBPeripheralManager, char: CBMutableCharacteristic) {
        while let next = pendingNotifyQueue.first {
            if pm.updateValue(next, for: char, onSubscribedCentrals: nil) {
                pendingNotifyQueue.removeFirst()
            } else {
                break
            }
        }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        guard let notifyChar = txNotifyChar else { return }
        drainPendingNotifyQueue(pm: peripheral, char: notifyChar)
    }

    private func encodeOutgoingPayload(_ frame: WireFrame) -> Data {
        let isHandshake = frame.messageType == .pairHello || frame.messageType == .pairCommit || frame.messageType == .pairAck
        if !isHandshake,
           let peer = (try? trustStore.allPeers())?.first,
           let cipher = try? BluetoothSessionCipher(sessionKey: peer.bluetoothSessionKey, senderTag: "MAC0"),
           let sealed = try? cipher.seal(frame: frame) {
            return sealed
        }
        return FrameEncoder.encode(frame)
    }

    private func decodeIncomingPayload(_ data: Data) -> WireFrame? {
        if BluetoothSessionCipher.isEncryptedEnvelope(data),
           let peers = try? trustStore.allPeers() {
            for peer in peers {
                if let cipher = try? BluetoothSessionCipher(sessionKey: peer.bluetoothSessionKey),
                   let frame = try? cipher.openFrame(from: data) {
                    return frame
                }
            }
            return nil
        }
        return try? WireFrame.decode(from: data)
    }

    private func configureAndAdvertise() {
        guard let pm = peripheralManager, pm.state == .poweredOn else { return }
        if !serviceAdded {
            let rxChar = CBMutableCharacteristic(
                type: Self.rxWriteCharUUID,
                properties: [.write, .writeWithoutResponse],
                value: nil,
                permissions: [.writeable]
            )
            let txChar = CBMutableCharacteristic(
                type: Self.txNotifyCharUUID,
                properties: [.notify],
                value: nil,
                permissions: [.readable]
            )
            let psmChar = CBMutableCharacteristic(
                type: Self.l2capPsmCharUUID,
                properties: [.read, .notify],
                value: nil,
                permissions: [.readable]
            )
            self.txNotifyChar = txChar
            self.l2capPsmChar = psmChar

            let service = CBMutableService(type: Self.serviceUUID, primary: true)
            service.characteristics = [rxChar, txChar, psmChar]
            pm.add(service)
            pm.publishL2CAPChannel(withEncryption: true)
            serviceAdded = true
        }
        if !pm.isAdvertising {
            pm.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
                CBAdvertisementDataLocalNameKey: advertisedName
            ])
        }
        updateDiagnostics(isPoweredOn: true, isAdvertising: true, message: "BLE GATT Peripheral Active")
    }

    private func updateDiagnostics(
        isPoweredOn: Bool? = nil,
        isAdvertising: Bool? = nil,
        subscribers: Int? = nil,
        l2capPsm: UInt16? = nil,
        message: String? = nil
    ) {
        let updated = BlePeripheralDiagnostics(
            isPoweredOn: isPoweredOn ?? diagnostics.isPoweredOn,
            isAdvertising: isAdvertising ?? diagnostics.isAdvertising,
            subscribedCentralsCount: subscribers ?? diagnostics.subscribedCentralsCount,
            l2capPsm: l2capPsm ?? diagnostics.l2capPsm,
            lastStatusMessage: message ?? diagnostics.lastStatusMessage
        )
        diagnostics = updated
        onDiagnosticsChanged?(updated)
    }

    // MARK: - CBPeripheralManagerDelegate

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            configureAndAdvertise()
        } else {
            updateDiagnostics(
                isPoweredOn: false,
                isAdvertising: false,
                message: "Bluetooth state: \(peripheral.state.rawValue)"
            )
        }
    }

    public func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            updateDiagnostics(isAdvertising: false, message: "Adv error: \(error.localizedDescription)")
        } else {
            updateDiagnostics(isAdvertising: true, message: "Advertising GATT + Pairing")
        }
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didPublishL2CAPChannel PSM: CBL2CAPPSM,
        error: Error?
    ) {
        guard error == nil else { return }
        var psmBE = UInt16(PSM).bigEndian
        let psmData = withUnsafeBytes(of: &psmBE) { Data($0) }
        l2capPsmChar?.value = psmData
        if let psmChar = l2capPsmChar {
            peripheral.updateValue(psmData, for: psmChar, onSubscribedCentrals: nil)
        }
        updateDiagnostics(l2capPsm: UInt16(PSM), message: "L2CAP PSM \(PSM) ready")
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didOpen channel: CBL2CAPChannel?,
        error: Error?
    ) {
        guard let channel, error == nil else { return }
        activeL2CapChannel = channel
        channel.inputStream?.delegate = self
        channel.inputStream?.schedule(in: .main, forMode: .default)
        channel.inputStream?.open()
        channel.outputStream?.open()
        updateDiagnostics(message: "L2CAP Bluetooth Stream Connected")
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
            subscribedCentrals.append(central)
        }
        updateDiagnostics(subscribers: subscribedCentrals.count, message: "Android subscribed over GATT")
    }

    public func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        reassembler.reset()
        updateDiagnostics(subscribers: subscribedCentrals.count, message: "Subscriber disconnected")
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            guard req.characteristic.uuid == Self.rxWriteCharUUID,
                  let data = req.value else {
                peripheral.respond(to: req, withResult: .attributeNotFound)
                continue
            }
            if data.first == BluetoothPacketCodec.dataTag {
                let (_, assembled, ack) = reassembler.ingest(data)
                if let ack, let txChar = txNotifyChar {
                    peripheral.updateValue(ack.encode(), for: txChar, onSubscribedCentrals: [req.central])
                }
                if let assembled, let frame = decodeIncomingPayload(assembled) {
                    deliverVerified(frame)
                }
            } else {
                if let frame = decodeIncomingPayload(data) {
                    deliverVerified(frame)
                } else {
                    legacyDecoder.append(data)
                    if let frames = try? legacyDecoder.drainFrames() {
                        for frame in frames {
                            deliverVerified(frame)
                        }
                    } else {
                        legacyDecoder.reset()
                    }
                }
            }
            peripheral.respond(to: req, withResult: .success)
        }
    }

    private func deliverVerified(_ frame: WireFrame) {
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

    // MARK: - StreamDelegate (L2CAP CoC Inbound)

    public func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        let shouldHandle = eventCode.contains(.hasBytesAvailable)
        let shouldClose = eventCode.contains(.endEncountered) || eventCode.contains(.errorOccurred)
        guard shouldHandle || shouldClose else { return }
        queue.async { [weak self] in
            guard let self, let inStream = self.activeL2CapChannel?.inputStream else { return }
            if shouldHandle {
                for payload in self.l2capFramer.readAvailable(from: inStream) {
                    if let frame = self.decodeIncomingPayload(payload) {
                        self.deliverVerified(frame)
                    }
                }
            }
            if shouldClose {
                self.activeL2CapChannel?.inputStream?.close()
                self.activeL2CapChannel?.outputStream?.close()
                self.activeL2CapChannel = nil
                self.l2capFramer.reset()
                self.updateDiagnostics(message: "L2CAP stream closed; falling back to GATT")
            }
        }
    }
}
