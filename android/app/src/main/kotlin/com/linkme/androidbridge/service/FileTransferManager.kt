package com.linkme.androidbridge.service

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.linkme.androidbridge.core.protocol.FileChunkPacket
import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.FileResponsePayload
import com.linkme.androidbridge.core.protocol.FilenameSanitizer
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class FileTransferProgress(
    val transferId: UUID,
    val filename: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSecond: Double
)

class FileTransferException(message: String) : Exception(message)

/**
 * Feature 3: Streaming 64 KB chunked AirDrop-style file transfer.
 * Streams files through a 65,536-byte buffer while computing incremental SHA-256 without loading whole files into RAM.
 */
class FileTransferManager(
    private val stagingRoot: File
) {
    private val cancelledIds = ConcurrentHashMap.newKeySet<UUID>()
    private val activeReceives = ConcurrentHashMap<UUID, ActiveReceive>()

    private class ActiveReceive(
        val offer: FileOfferPayload,
        val partFile: File,
        val finalFile: File,
        val outStream: FileOutputStream,
        val digest: MessageDigest,
        var bytesWritten: Long,
        val startedAtMs: Long
    )

    fun cancelTransfer(transferId: UUID) {
        cancelledIds.add(transferId)
        activeReceives.remove(transferId)?.let { active ->
            runCatching { active.outStream.close() }
            active.partFile.delete()
        }
    }

    fun streamOutboundFile(
        sourceFile: File,
        transferId: UUID = UUID.randomUUID(),
        mimeType: String = "application/octet-stream",
        onProgress: ((FileTransferProgress) -> Unit)? = null,
        emitFrame: (WireFrame) -> Unit
    ): FileOfferPayload {
        val totalBytes = sourceFile.length()
        if (totalBytes <= 0L) throw FileTransferException("File is empty or missing")

        val fileSha256 = streamingSha256Hex(FileInputStream(sourceFile))
        val totalChunks = ((totalBytes + CHUNK_SIZE - 1L) / CHUNK_SIZE).toInt()
        val safeFilename = FilenameSanitizer.sanitize(sourceFile.name)

        val offer = FileOfferPayload(
            transferId = transferId.toString().lowercase(),
            filename = safeFilename,
            byteSize = totalBytes,
            sha256 = fileSha256,
            chunkSize = CHUNK_SIZE,
            totalChunks = totalChunks,
            mimeType = mimeType
        )
        var seq = 1L
        emitFrame(
            WireFrame(
                messageType = WireMessageType.FILE_OFFER,
                sequenceId = seq,
                payload = Json.encodeToString(offer).toByteArray(Charsets.UTF_8)
            )
        )

        val buffer = ByteArray(CHUNK_SIZE)
        val startedAt = System.currentTimeMillis()
        var chunkIndex = 0
        var bytesSent = 0L

        FileInputStream(sourceFile).use { input ->
            while (true) {
                if (transferId in cancelledIds) throw FileTransferException("Transfer cancelled")
                val read = input.read(buffer)
                if (read <= 0) break

                val slice = if (read == buffer.size) buffer else buffer.copyOf(read)
                val chunkSha = MessageDigest.getInstance("SHA-256").digest(slice)
                val packet = FileChunkPacket(
                    transferId = transferId,
                    chunkIndex = chunkIndex,
                    chunkSha256 = chunkSha,
                    data = slice
                )
                seq += 1L
                emitFrame(WireFrame(WireMessageType.FILE_CHUNK, seq, packet.encode()))

                bytesSent += read.toLong()
                chunkIndex += 1
                val elapsedSec = maxOf(0.001, (System.currentTimeMillis() - startedAt) / 1000.0)
                onProgress?.invoke(
                    FileTransferProgress(
                        transferId = transferId,
                        filename = safeFilename,
                        bytesTransferred = bytesSent,
                        totalBytes = totalBytes,
                        bytesPerSecond = bytesSent / elapsedSec
                    )
                )
            }
        }
        return offer
    }

    fun beginIncomingTransfer(offer: FileOfferPayload): FileResponsePayload {
        val transferUuid = UUID.fromString(offer.transferId)
        stagingRoot.mkdirs()
        val finalFile = FilenameSanitizer.resolveSafeFile(offer.filename, stagingRoot)
        val partFile = File(finalFile.parentFile, "${finalFile.name}.part")
        if (partFile.exists()) partFile.delete()

        val state = ActiveReceive(
            offer = offer,
            partFile = partFile,
            finalFile = finalFile,
            outStream = FileOutputStream(partFile),
            digest = MessageDigest.getInstance("SHA-256"),
            bytesWritten = 0L,
            startedAtMs = System.currentTimeMillis()
        )
        activeReceives[transferUuid] = state
        return FileResponsePayload(transferId = offer.transferId, accepted = true, resumeChunkIndex = 0)
    }

    fun receiveChunk(packet: FileChunkPacket): FileTransferProgress {
        val state = activeReceives[packet.transferId]
            ?: throw FileTransferException("Unknown transfer ${packet.transferId}")
        val actualSha = MessageDigest.getInstance("SHA-256").digest(packet.data)
        if (!MessageDigest.isEqual(actualSha, packet.chunkSha256)) {
            cancelTransfer(packet.transferId)
            throw FileTransferException("Chunk ${packet.chunkIndex} SHA-256 mismatch")
        }

        state.outStream.write(packet.data)
        state.digest.update(packet.data)
        state.bytesWritten += packet.data.size.toLong()

        val elapsedSec = maxOf(0.001, (System.currentTimeMillis() - state.startedAtMs) / 1000.0)
        return FileTransferProgress(
            transferId = packet.transferId,
            filename = state.finalFile.name,
            bytesTransferred = state.bytesWritten,
            totalBytes = state.offer.byteSize,
            bytesPerSecond = state.bytesWritten / elapsedSec
        )
    }

    fun finalizeIncomingTransfer(transferId: UUID): File {
        val state = activeReceives.remove(transferId)
            ?: throw FileTransferException("Unknown transfer $transferId")
        state.outStream.close()

        val actualHex = state.digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualHex.equals(state.offer.sha256, ignoreCase = true)) {
            state.partFile.delete()
            throw FileTransferException("Final SHA-256 mismatch: expected=${state.offer.sha256}, actual=$actualHex")
        }

        if (state.finalFile.exists()) state.finalFile.delete()
        if (!state.partFile.renameTo(state.finalFile)) {
            state.partFile.delete()
            throw FileTransferException("Failed to rename .part file")
        }
        return state.finalFile
    }

    fun exportToMediaStoreDownloads(context: Context, verifiedFile: File, mimeType: String): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return android.net.Uri.fromFile(verifiedFile)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FilenameSanitizer.sanitize(verifiedFile.name))
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Link-Me")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(verifiedFile).use { input ->
                val buf = ByteArray(CHUNK_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
            }
        }
        return uri
    }

    companion object {
        const val CHUNK_SIZE = 65_536 // 64 KB

        fun streamingSha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(CHUNK_SIZE)
            input.use { stream ->
                while (true) {
                    val read = stream.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
