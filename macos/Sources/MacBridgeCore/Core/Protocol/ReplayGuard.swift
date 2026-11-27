import Foundation

/// 64-bit sliding anti-replay window per paired peer session.
/// Rejects sequenceId == 0, duplicate sequenceIds, and stale sequences older than 64 packets.
public actor ReplayGuard {
    public private(set) var highestSequenceId: UInt64 = 0
    private var windowBitmap: UInt64 = 0

    public init() {}

    public func validateAndAccept(_ sequenceId: UInt64) throws {
        guard sequenceId > 0 else {
            throw ProtocolError.replayDetected(received: sequenceId, highestAccepted: highestSequenceId)
        }

        if highestSequenceId == 0 {
            highestSequenceId = sequenceId
            windowBitmap = 1
            return
        }

        if sequenceId > highestSequenceId {
            let delta = sequenceId - highestSequenceId
            if delta >= 64 {
                windowBitmap = 1
            } else {
                windowBitmap = (windowBitmap << delta) | 1
            }
            highestSequenceId = sequenceId
            return
        }

        let diff = highestSequenceId - sequenceId
        guard diff < 64 else {
            throw ProtocolError.replayDetected(received: sequenceId, highestAccepted: highestSequenceId)
        }

        let bitMask: UInt64 = 1 << diff
        guard (windowBitmap & bitMask) == 0 else {
            throw ProtocolError.replayDetected(received: sequenceId, highestAccepted: highestSequenceId)
        }

        windowBitmap |= bitMask
    }

    public func reset() {
        highestSequenceId = 0
        windowBitmap = 0
    }
}
