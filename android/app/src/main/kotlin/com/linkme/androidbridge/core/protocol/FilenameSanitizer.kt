package com.linkme.androidbridge.core.protocol

import java.io.File

class UnsafeFilenameException(reason: String) : IllegalArgumentException("UNSAFE_FILENAME: $reason")

/**
 * Strips path traversal, directory separators, NUL/control bytes, and leading dots;
 * safely truncates to 200 UTF-8 bytes preserving extensions; falls back to "unnamed_file".
 */
object FilenameSanitizer {
    const val MAX_UTF8_BYTES = 200
    const val FALLBACK_FILENAME = "unnamed_file"

    fun sanitize(rawFilename: String): String {
        val cleaned = buildString {
            for (ch in rawFilename) {
                val code = ch.code
                if (code >= 0x20 && code != 0x7F) {
                    append(ch)
                }
            }
        }

        var candidate = cleaned
        if (candidate.length >= 2 && candidate[1] == ':' && candidate[0].isLetter()) {
            candidate = candidate.substring(2)
        }

        candidate = candidate.split('/', '\\').lastOrNull().orEmpty()
        candidate = candidate.replace("..", "")
        while (candidate.startsWith('.')) {
            candidate = candidate.drop(1)
        }
        candidate = candidate.trim()

        if (candidate.isEmpty()) {
            return FALLBACK_FILENAME
        }
        return truncatePreservingExtension(candidate, MAX_UTF8_BYTES)
    }

    fun validateStrict(rawFilename: String): String {
        if (rawFilename.isEmpty()) throw UnsafeFilenameException("empty filename")
        for (ch in rawFilename) {
            val code = ch.code
            if (code < 0x20 || code == 0x7F) {
                throw UnsafeFilenameException("control or NUL character")
            }
        }
        if (rawFilename.contains('/') || rawFilename.contains('\\') || rawFilename.contains(':')) {
            throw UnsafeFilenameException("path separator forbidden")
        }
        if (rawFilename == "." || rawFilename == ".." || rawFilename.contains("..")) {
            throw UnsafeFilenameException("traversal segment forbidden")
        }
        if (rawFilename.startsWith('.')) {
            throw UnsafeFilenameException("leading dot forbidden")
        }
        if (rawFilename.toByteArray(Charsets.UTF_8).size > MAX_UTF8_BYTES) {
            throw UnsafeFilenameException("exceeds 200 UTF-8 bytes")
        }
        return rawFilename
    }

    fun resolveSafeFile(rawFilename: String, rootDirectory: File): File {
        val safeName = sanitize(rawFilename)
        val canonicalRoot = rootDirectory.canonicalFile
        val target = File(canonicalRoot, safeName).canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separatorChar
        if (!target.path.startsWith(rootPrefix)) {
            throw UnsafeFilenameException("escapes staging root")
        }
        return target
    }

    private fun truncatePreservingExtension(filename: String, maxBytes: Int): String {
        if (filename.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return filename
        }
        val dotIndex = filename.lastIndexOf('.')
        val hasExt = dotIndex > 0 && dotIndex < filename.length - 1
        val dotExt = if (hasExt) filename.substring(dotIndex) else ""
        val dotExtBytes = dotExt.toByteArray(Charsets.UTF_8).size

        if (dotExtBytes >= maxBytes) {
            return prefixWithinBytes(filename, maxBytes)
        }

        val stem = if (hasExt) filename.substring(0, dotIndex) else filename
        val truncatedStem = prefixWithinBytes(stem, maxBytes - dotExtBytes)
        val combined = truncatedStem + dotExt
        return combined.ifEmpty { FALLBACK_FILENAME }
    }

    private fun prefixWithinBytes(text: String, limit: Int): String {
        val sb = StringBuilder()
        var used = 0
        for (ch in text) {
            val bytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + bytes > limit) break
            sb.append(ch)
            used += bytes
        }
        return sb.toString()
    }
}
