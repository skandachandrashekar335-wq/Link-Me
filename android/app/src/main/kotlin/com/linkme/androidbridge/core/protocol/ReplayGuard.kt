package com.linkme.androidbridge.core.protocol

/**
 * 64-bit sliding anti-replay window per paired peer session.
 */
class ReplayGuard {
    var highestSequenceId: Long = 0L
        private set
    private var windowBitmap: Long = 0L

    @Synchronized
    fun validateAndAccept(sequenceId: Long) {
        if (sequenceId <= 0L) {
            throw ProtocolError.ReplayDetected(sequenceId, highestSequenceId)
        }

        if (highestSequenceId == 0L) {
            highestSequenceId = sequenceId
            windowBitmap = 1L
            return
        }

        if (sequenceId > highestSequenceId) {
            val delta = sequenceId - highestSequenceId
            windowBitmap = if (delta >= 64L) {
                1L
            } else {
                (windowBitmap shl delta.toInt()) or 1L
            }
            highestSequenceId = sequenceId
            return
        }

        val diff = highestSequenceId - sequenceId
        if (diff >= 64L) {
            throw ProtocolError.ReplayDetected(sequenceId, highestSequenceId)
        }

        val bitMask = 1L shl diff.toInt()
        if ((windowBitmap and bitMask) != 0L) {
            throw ProtocolError.ReplayDetected(sequenceId, highestSequenceId)
        }

        windowBitmap = windowBitmap or bitMask
    }

    @Synchronized
    fun reset() {
        highestSequenceId = 0L
        windowBitmap = 0L
    }
}
