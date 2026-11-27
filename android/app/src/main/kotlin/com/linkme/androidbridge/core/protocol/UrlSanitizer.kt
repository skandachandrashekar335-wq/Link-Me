package com.linkme.androidbridge.core.protocol

import java.net.URI

class UnsafeUrlException(message: String) : IllegalArgumentException(message)

/**
 * Strict URL validator allowing ONLY http:// and https:// URLs with a non-empty host.
 * Rejects file://, javascript:, data:, intent:, content:, and control/whitespace characters.
 */
object UrlSanitizer {
    const val MAX_URL_LENGTH = 2048

    fun validateStrict(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > MAX_URL_LENGTH) {
            throw UnsafeUrlException("URL is empty or exceeds 2048 bytes")
        }

        for (ch in trimmed) {
            if (ch.code <= 0x20 || ch.code == 0x7F) {
                throw UnsafeUrlException("URL contains whitespace or control character")
            }
        }

        val parsed = try {
            URI(trimmed)
        } catch (ex: Exception) {
            throw UnsafeUrlException("Malformed URI: ${ex.message}")
        }

        val scheme = parsed.scheme?.lowercase() ?: throw UnsafeUrlException("Missing URI scheme")
        if (scheme != "http" && scheme != "https") {
            throw UnsafeUrlException("Disallowed URI scheme: $scheme")
        }

        val host = parsed.host
        if (host.isNullOrBlank()) {
            throw UnsafeUrlException("URL missing host")
        }

        return parsed.toASCIIString()
    }
}
