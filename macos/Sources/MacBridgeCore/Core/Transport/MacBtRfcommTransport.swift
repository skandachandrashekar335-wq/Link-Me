import Foundation
@preconcurrency import IOBluetooth

private final class ChannelBox: @unchecked Sendable {
    var channel: IOBluetoothRFCOMMChannel?
    init(_ channel: IOBluetoothRFCOMMChannel?) { self.channel = channel }
}

/// High-throughput, continuous Bluetooth Classic RFCOMM stream transport.
/// Replaces fragile BLE GATT chunking/CCCD negotiation with rock-solid streaming sockets.
public final class MacBtRfcommTransport: NSObject, IOBluetoothRFCOMMChannelDelegate, @unchecked Sendable {
    public static let sharedSppUuid = "00001101-0000-1000-8000-00805F9B34FB"
    public static let defaultChannelID: BluetoothRFCOMMChannelID = 1

    private let queue = DispatchQueue(label: "com.linkme.macbt.rfcomm", qos: .userInitiated)
    private var sdpServiceRecord: IOBluetoothSDPServiceRecord?
    private var openNotification: IOBluetoothUserNotification?
    private let activeChannelBox = ChannelBox(nil)
    private var frameDecoder = FrameDecoder()

    public var onConnected: (@Sendable () -> Void)?
    public var onDisconnected: (@Sendable () -> Void)?
    public var onFrameReceived: (@Sendable (WireFrame) -> Void)?
    public var onDiagnostic: (@Sendable (String) -> Void)?

    private var _isConnected: Bool = false
    public var isConnected: Bool {
        queue.sync { _isConnected && activeChannelBox.channel?.isOpen() == true }
    }

    public override init() {
        super.init()
    }

    deinit {
        stop()
    }

    /// Queries the host Mac's Bluetooth controller hardware MAC address (e.g. "7C:D1:AD:B7:DF:09").
    public static func hostBluetoothAddress() -> String? {
        guard let controller = IOBluetoothHostController.default() else { return nil }
        let raw = controller.addressAsString() ?? ""
        if raw.isEmpty { return nil }
        return raw.replacingOccurrences(of: "-", with: ":").uppercased()
    }

    /// Starts advertising the LinkMe RFCOMM SDP service and listens for incoming connections from Android.
    public func startListening(channelID: BluetoothRFCOMMChannelID = defaultChannelID) -> Bool {
        queue.sync {
            stopInternal()

            let serviceDict: [String: Any] = [
                "0100 - ServiceName*": "LinkMeBridge",
                "0001 - ServiceClassIDList": [
                    IOBluetoothSDPUUID(uuid16: 0x1101) // Serial Port Profile (SPP)
                ],
                "0004 - ProtocolDescriptorList": [
                    [
                        IOBluetoothSDPUUID(uuid16: 0x0100) // L2CAP
                    ],
                    [
                        IOBluetoothSDPUUID(uuid16: 0x0003), // RFCOMM
                        NSNumber(value: channelID)
                    ]
                ]
            ]

            guard let record = IOBluetoothSDPServiceRecord.publishedServiceRecord(with: serviceDict) else {
                onDiagnostic?("RFCOMM: Failed to publish SDP service record")
                return false
            }
            self.sdpServiceRecord = record

            let notif = IOBluetoothRFCOMMChannel.register(
                forChannelOpenNotifications: self,
                selector: #selector(self.channelOpenedNotification(_:channel:)),
                withChannelID: channelID,
                direction: kIOBluetoothUserNotificationChannelDirectionIncoming
            )
            self.openNotification = notif

            onDiagnostic?("RFCOMM: Listening on channel \(channelID), SDP published")
            return true
        }
    }

    @objc private func channelOpenedNotification(_ notification: IOBluetoothUserNotification, channel: IOBluetoothRFCOMMChannel) {
        let boxed = ChannelBox(channel)
        queue.async { [weak self] in
            guard let self, let ch = boxed.channel else { return }
            self.onDiagnostic?("RFCOMM: Incoming channel opened from peer \(ch.getDevice()?.nameOrAddress ?? "unknown")")
            self.attachChannel(ch)
        }
    }

    /// Dials an outbound RFCOMM channel to the specified peer device Bluetooth MAC address.
    public func connect(toAddress address: String, channelID: BluetoothRFCOMMChannelID = defaultChannelID) -> Bool {
        let normalized = address.replacingOccurrences(of: ":", with: "-").lowercased()
        guard let device = IOBluetoothDevice(addressString: normalized) else {
            onDiagnostic?("RFCOMM: Invalid Bluetooth device address \(address)")
            return false
        }

        var channel: IOBluetoothRFCOMMChannel? = nil
        let result = device.openRFCOMMChannelSync(&channel, withChannelID: channelID, delegate: self)
        if result == kIOReturnSuccess, let channel {
            let boxed = ChannelBox(channel)
            queue.async { [weak self] in
                guard let self, let ch = boxed.channel else { return }
                self.onDiagnostic?("RFCOMM: Outbound channel connected to \(address)")
                self.attachChannel(ch)
            }
            return true
        } else {
            onDiagnostic?("RFCOMM: Failed to connect to \(address) (err \(result))")
            return false
        }
    }

    private func attachChannel(_ channel: IOBluetoothRFCOMMChannel) {
        if let old = activeChannelBox.channel, old != channel {
            old.close()
        }
        activeChannelBox.channel = channel
        _isConnected = true
        frameDecoder = FrameDecoder()
        channel.setDelegate(self)
        onDiagnostic?("RFCOMM: Attached active channel \(channel.getID())")
        onConnected?()
    }

    /// Transmits a framed WireMessage over the RFCOMM stream socket in chunks.
    @discardableResult
    public func sendFrame(_ frame: WireFrame) -> Bool {
        let encoded = frame.encode()
        return sendData(encoded)
    }

    public func sendData(_ data: Data) -> Bool {
        queue.sync {
            guard _isConnected, let channel = activeChannelBox.channel, channel.isOpen() else {
                return false
            }
            var offset = 0
            let total = data.count
            let chunkSize = 4096

            while offset < total {
                let thisChunk = min(chunkSize, total - offset)
                let sub = data.subdata(in: offset..<(offset + thisChunk))
                let writeResult = sub.withUnsafeBytes { ptr -> IOReturn in
                    guard let base = ptr.baseAddress else { return kIOReturnBadArgument }
                    return channel.writeSync(UnsafeMutableRawPointer(mutating: base), length: UInt16(thisChunk))
                }
                if writeResult != kIOReturnSuccess {
                    onDiagnostic?("RFCOMM: writeSync failed with error \(writeResult)")
                    return false
                }
                offset += thisChunk
            }
            return true
        }
    }

    public func stop() {
        queue.sync {
            stopInternal()
        }
    }

    private func stopInternal() {
        _isConnected = false
        openNotification?.unregister()
        openNotification = nil
        if let sdp = sdpServiceRecord {
            sdp.remove()
            sdpServiceRecord = nil
        }
        if let chan = activeChannelBox.channel {
            chan.setDelegate(nil)
            chan.close()
            activeChannelBox.channel = nil
        }
        frameDecoder = FrameDecoder()
    }

    // MARK: - IOBluetoothRFCOMMChannelDelegate

    public func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
        guard let dataPointer, dataLength > 0 else { return }
        let bytes = Data(bytes: dataPointer, count: dataLength)

        queue.async { [weak self] in
            guard let self else { return }
            self.frameDecoder.append(bytes)
            while let frame = try? self.frameDecoder.nextFrame() {
                self.onFrameReceived?(frame)
            }
        }
    }

    public func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) {
        queue.async { [weak self] in
            guard let self else { return }
            if self.activeChannelBox.channel == rfcommChannel {
                self.activeChannelBox.channel = nil
                self._isConnected = false
                self.onDiagnostic?("RFCOMM: Channel closed by remote peer")
                self.onDisconnected?()
            }
        }
    }

    public func rfcommChannelWriteComplete(_ rfcommChannel: IOBluetoothRFCOMMChannel!, refcon: UnsafeMutableRawPointer!, status: IOReturn) {
        // Asynchronous completion tracking if needed
    }
}
