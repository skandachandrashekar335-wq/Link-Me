import CryptoKit
import Foundation

public struct FileTransferProgress: Sendable, Equatable {
    public let transferId: UUID
    public let filename: String
    public let bytesTransferred: UInt64
    public let totalBytes: UInt64
    public let bytesPerSecond: Double

    public var fractionCompleted: Double {
        guard totalBytes > 0 else { return 0 }
        return min(1.0, Double(bytesTransferred) / Double(totalBytes))
    }
}

public enum FileTransferError: Error, Equatable, Sendable {
    case cancelled
    case fileEmptyOrMissing
    case chunkHashMismatch(chunkIndex: UInt32)
    case finalDigestMismatch(expected: String, actual: String)
    case unexpectedTransferId
}

/// Feature 3: Streaming 64 KB chunked AirDrop-style file sender & receiver.
/// Never loads entire files into RAM; stages incoming bytes in `.part` files before atomic SHA-256 verification.
public actor FileTransferManager {
    public static let chunkSize = 65_536 // 64 KB

    private let destinationRoot: URL
    private var cancelledTransfers: Set<UUID> = []
    private var activeReceives: [UUID: ActiveReceiveState] = [:]
    private var responseWaiters: [String: CheckedContinuation<Bool, Never>] = [:]
    private var completedResponses: [String: Bool] = [:]

    private struct ActiveReceiveState {
        let offer: FileOfferPayload
        let partURL: URL
        let finalURL: URL
        let handle: FileHandle
        var hasher: SHA256
        var bytesWritten: UInt64
        var startedAt: Date
    }

    public init(destinationRoot: URL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Link-Me", isDirectory: true)) {
        self.destinationRoot = destinationRoot
    }

    public func cancelTransfer(_ transferId: UUID) {
        cancelledTransfers.insert(transferId)
        let key = transferId.uuidString.lowercased()
        if let cont = responseWaiters.removeValue(forKey: key) {
            cont.resume(returning: false)
        }
        if let active = activeReceives.removeValue(forKey: transferId) {
            try? active.handle.close()
            try? FileManager.default.removeItem(at: active.partURL)
        }
    }

    public func handleFileResponse(_ response: FileResponsePayload) {
        let key = response.transferId.lowercased()
        if let cont = responseWaiters.removeValue(forKey: key) {
            cont.resume(returning: response.accepted)
        } else {
            completedResponses[key] = response.accepted
        }
    }

    public func waitForAcceptance(transferId: UUID, timeoutSeconds: TimeInterval = 5.0) async -> Bool {
        let key = transferId.uuidString.lowercased()
        if let pre = completedResponses.removeValue(forKey: key) {
            return pre
        }
        return await withCheckedContinuation { continuation in
            responseWaiters[key] = continuation
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(timeoutSeconds))
                await self?.resolveTimedOutResponse(key: key)
            }
        }
    }

    private func resolveTimedOutResponse(key: String) {
        if let cont = responseWaiters.removeValue(forKey: key) {
            cont.resume(returning: false)
        }
    }

    public func streamOutboundFile(
        fileURL: URL,
        transferId: UUID = UUID(),
        mimeType: String = "application/octet-stream",
        chunkSize: Int = FileTransferManager.chunkSize,
        awaitOfferAcceptance: Bool = false,
        onProgress: (@Sendable (FileTransferProgress) async -> Void)? = nil,
        emitFrame: @Sendable (WireFrame) async throws -> Void
    ) async throws -> FileOfferPayload {
        let attrs = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let totalBytes = (attrs[.size] as? NSNumber)?.uint64Value ?? 0
        guard totalBytes > 0 else {
            throw FileTransferError.fileEmptyOrMissing
        }

        let effChunkSize = min(Self.chunkSize, max(512, chunkSize))
        // Pass 1: streaming SHA-256 without loading entire file into RAM
        let fileDigest = try Self.streamingSha256Hex(fileURL: fileURL)
        let totalChunks = UInt32((totalBytes + UInt64(effChunkSize) - 1) / UInt64(effChunkSize))
        let safeFilename = FilenameSanitizer.sanitize(fileURL.lastPathComponent)

        let offer = FileOfferPayload(
            transferId: transferId.uuidString.lowercased(),
            filename: safeFilename,
            byteSize: totalBytes,
            sha256: fileDigest,
            chunkSize: UInt32(effChunkSize),
            totalChunks: totalChunks,
            mimeType: mimeType
        )
        let offerData = try JSONEncoder().encode(offer)
        var seq: UInt64 = 1
        try await emitFrame(WireFrame(messageType: .fileOffer, sequenceId: seq, payload: offerData))

        if awaitOfferAcceptance {
            let accepted = await waitForAcceptance(transferId: transferId, timeoutSeconds: 6.0)
            guard accepted else {
                throw FileTransferError.cancelled
            }
        }

        // Pass 2: stream chunks sequentially with sliding-window progress
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }

        let start = Date()
        var chunkIndex: UInt32 = 0
        var bytesSent: UInt64 = 0

        while true {
            if cancelledTransfers.contains(transferId) {
                throw FileTransferError.cancelled
            }
            let chunkData = try handle.read(upToCount: effChunkSize) ?? Data()
            if chunkData.isEmpty { break }

            let chunkSha = Data(SHA256.hash(data: chunkData))
            let packet = try FileChunkPacket(
                transferId: transferId,
                chunkIndex: chunkIndex,
                chunkSha256: chunkSha,
                data: chunkData
            )
            seq &+= 1
            try await emitFrame(WireFrame(messageType: .fileChunk, sequenceId: seq, payload: packet.encode()))

            bytesSent += UInt64(chunkData.count)
            chunkIndex &+= 1

            let elapsed = max(0.001, Date().timeIntervalSince(start))
            await onProgress?(FileTransferProgress(
                transferId: transferId,
                filename: safeFilename,
                bytesTransferred: bytesSent,
                totalBytes: totalBytes,
                bytesPerSecond: Double(bytesSent) / elapsed
            ))
        }

        return offer
    }

    public func beginIncomingTransfer(offer: FileOfferPayload) throws -> FileResponsePayload {
        guard let uuid = UUID(uuidString: offer.transferId) else {
            throw FileTransferError.unexpectedTransferId
        }
        try FileManager.default.createDirectory(at: destinationRoot, withIntermediateDirectories: true)
        let finalURL = try FilenameSanitizer.resolveSafeURL(filename: offer.filename, inside: destinationRoot)
        let partURL = finalURL.appendingPathExtension("part")

        if FileManager.default.fileExists(atPath: partURL.path) {
            try FileManager.default.removeItem(at: partURL)
        }
        FileManager.default.createFile(atPath: partURL.path, contents: nil)
        let handle = try FileHandle(forWritingTo: partURL)

        activeReceives[uuid] = ActiveReceiveState(
            offer: offer,
            partURL: partURL,
            finalURL: finalURL,
            handle: handle,
            hasher: SHA256(),
            bytesWritten: 0,
            startedAt: Date()
        )
        return FileResponsePayload(transferId: offer.transferId, accepted: true, resumeChunkIndex: 0)
    }

    public func receiveChunk(_ packet: FileChunkPacket) throws -> FileTransferProgress {
        guard var state = activeReceives[packet.transferId] else {
            throw FileTransferError.unexpectedTransferId
        }
        let actualChunkHash = Data(SHA256.hash(data: packet.data))
        guard ConstantTime.equals(actualChunkHash, packet.chunkSha256) else {
            cancelTransfer(packet.transferId)
            throw FileTransferError.chunkHashMismatch(chunkIndex: packet.chunkIndex)
        }

        try state.handle.write(contentsOf: packet.data)
        state.hasher.update(data: packet.data)
        state.bytesWritten += UInt64(packet.data.count)
        activeReceives[packet.transferId] = state

        let elapsed = max(0.001, Date().timeIntervalSince(state.startedAt))
        return FileTransferProgress(
            transferId: packet.transferId,
            filename: state.finalURL.lastPathComponent,
            bytesTransferred: state.bytesWritten,
            totalBytes: state.offer.byteSize,
            bytesPerSecond: Double(state.bytesWritten) / elapsed
        )
    }

    public func finalizeIncomingTransfer(_ transferId: UUID) throws -> URL {
        guard let state = activeReceives.removeValue(forKey: transferId) else {
            throw FileTransferError.unexpectedTransferId
        }
        try state.handle.close()

        let finalDigest = state.hasher.finalize().hexString
        guard finalDigest == state.offer.sha256.lowercased() else {
            try? FileManager.default.removeItem(at: state.partURL)
            throw FileTransferError.finalDigestMismatch(
                expected: state.offer.sha256.lowercased(),
                actual: finalDigest
            )
        }

        let resolvedFinalURL = Self.resolveUniqueDestination(
            directory: state.finalURL.deletingLastPathComponent(),
            filename: state.finalURL.lastPathComponent
        )
        try FileManager.default.moveItem(at: state.partURL, to: resolvedFinalURL)
        return resolvedFinalURL
    }

    public static func resolveUniqueDestination(
        directory: URL,
        filename: String,
        fileExists: (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }
    ) -> URL {
        let initial = directory.appendingPathComponent(filename)
        if !fileExists(initial.path) {
            return initial
        }
        let ext = (filename as NSString).pathExtension
        let stem = (filename as NSString).deletingPathExtension
        for index in 1...9999 {
            let candidateName = ext.isEmpty ? "\(stem) (\(index))" : "\(stem) (\(index)).\(ext)"
            let candidate = directory.appendingPathComponent(candidateName)
            if !fileExists(candidate.path) {
                return candidate
            }
        }
        let fallback = ext.isEmpty
            ? "\(stem)-\(UUID().uuidString.prefix(8))"
            : "\(stem)-\(UUID().uuidString.prefix(8)).\(ext)"
        return directory.appendingPathComponent(fallback)
    }

    public static func streamingSha256Hex(fileURL: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = try handle.read(upToCount: chunkSize) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().hexString
    }
}
