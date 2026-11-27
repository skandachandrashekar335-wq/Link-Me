import AppKit
import CoreGraphics
import CoreImage
import CoreMedia
import Foundation
@preconcurrency import ScreenCaptureKit

/// Captures macOS display frames using ScreenCaptureKit and compresses them into
/// 720p max-width JPEG frames for encrypted 0x0C SCREEN_FRAME streaming.
public final class ScreenMirrorManager: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])
    private let captureQueue = DispatchQueue(label: "com.linkme.macbridge.screenmirror", qos: .userInteractive)

    private var stream: SCStream?
    private var isEncodingFrame = false
    private var lastFrameSentAt: Date = .distantPast
    private var targetFps: Int = 15
    private var targetQuality: Double = 0.55
    private var maxWidth: Int = 1280
    private var frameHandler: (@Sendable (ScreenFramePacket) -> Void)?
    private var statusHandler: (@Sendable (String) -> Void)?

    public private(set) var isCapturing: Bool = false

    public func updateAdaptiveSettings(fps: Int, qualityPercent: Int, overBleOnly: Bool) {
        lock.withLock {
            self.targetFps = overBleOnly ? min(3, max(1, fps)) : min(30, max(1, fps))
            self.targetQuality = Double(min(90, max(25, qualityPercent))) / 100.0
            self.maxWidth = overBleOnly ? 640 : 1280
        }
    }

    public func startSharing(
        fps: Int = 15,
        qualityPercent: Int = 55,
        overBleOnly: Bool = false,
        onFrame: @escaping @Sendable (ScreenFramePacket) -> Void,
        onStatus: (@Sendable (String) -> Void)? = nil
    ) async throws {
        await stopSharing()
        updateAdaptiveSettings(fps: fps, qualityPercent: qualityPercent, overBleOnly: overBleOnly)
        lock.withLock {
            self.frameHandler = onFrame
            self.statusHandler = onStatus
        }

        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first else {
            onStatus?("No display available for ScreenCaptureKit")
            return
        }

        let filter = SCContentFilter(display: display, excludingWindows: [])
        let config = SCStreamConfiguration()
        let srcW = max(1, Int(display.width))
        let srcH = max(1, Int(display.height))
        let capW = lock.withLock { self.maxWidth }
        let scale = min(1.0, Double(capW) / Double(srcW))
        config.width = max(320, Int((Double(srcW) * scale).rounded()))
        config.height = max(240, Int((Double(srcH) * scale).rounded()))
        let effFps = lock.withLock { self.targetFps }
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(max(1, effFps)))
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.showsCursor = true
        config.queueDepth = 3

        let scStream = SCStream(filter: filter, configuration: config, delegate: self)
        try scStream.addStreamOutput(self, type: .screen, sampleHandlerQueue: captureQueue)
        try await scStream.startCapture()

        lock.withLock {
            self.stream = scStream
            self.isCapturing = true
        }
        onStatus?("Sharing macOS \(config.width)×\(config.height) @ \(effFps) fps")
    }

    public func stopSharing() async {
        let active = lock.withLock { () -> SCStream? in
            let current = self.stream
            self.stream = nil
            self.isCapturing = false
            return current
        }
        if let active {
            try? await active.stopCapture()
        }
    }

    public func stream(_ stream: SCStream, didStopWithError error: any Error) {
        let status = lock.withLock { () -> ((String) -> Void)? in
            self.isCapturing = false
            self.stream = nil
            return self.statusHandler
        }
        status?("Screen capture stopped: \(error.localizedDescription)")
    }

    public func stream(
        _ stream: SCStream,
        didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of type: SCStreamOutputType
    ) {
        guard type == .screen, sampleBuffer.isValid,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }

        let now = Date()
        let shouldEncode: (Bool, Double, Int, (@Sendable (ScreenFramePacket) -> Void)?) = lock.withLock {
            let minInterval = 1.0 / Double(max(1, self.targetFps))
            guard !self.isEncodingFrame,
                  now.timeIntervalSince(self.lastFrameSentAt) >= minInterval else {
                return (false, self.targetQuality, self.maxWidth, nil)
            }
            self.isEncodingFrame = true
            self.lastFrameSentAt = now
            return (true, self.targetQuality, self.maxWidth, self.frameHandler)
        }
        guard shouldEncode.0, let handler = shouldEncode.3 else { return }
        defer {
            lock.withLock { self.isEncodingFrame = false }
        }

        let rawImage = CIImage(cvPixelBuffer: pixelBuffer)
        let extent = rawImage.extent
        guard extent.width > 1, extent.height > 1 else { return }

        let targetScale = min(1.0, CGFloat(shouldEncode.2) / extent.width)
        let scaledImage = targetScale < 0.99
            ? rawImage.transformed(by: CGAffineTransform(scaleX: targetScale, y: targetScale))
            : rawImage
        let outWidth = max(1, Int(scaledImage.extent.width.rounded()))
        let outHeight = max(1, Int(scaledImage.extent.height.rounded()))

        guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let jpegData = ciContext.jpegRepresentation(
                of: scaledImage,
                colorSpace: colorSpace,
                options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: shouldEncode.1]
              ),
              jpegData.count <= ScreenFramePacket.maxJpegBytes else {
            return
        }

        let tsMs = UInt64(now.timeIntervalSince1970 * 1000)
        if let packet = try? ScreenFramePacket(
            source: ScreenFrameSource.macos.rawValue,
            width: UInt16(clamping: outWidth),
            height: UInt16(clamping: outHeight),
            timestampMs: tsMs,
            jpegData: jpegData
        ) {
            handler(packet)
        }
    }
}
