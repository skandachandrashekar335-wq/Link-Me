package com.linkme.androidbridge.notifyaddon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonIpcContractTest {
    @Test
    fun `ignores core and addon packages`() {
        assertTrue(AddonIpcContract.shouldIgnorePackage(AddonIpcContract.CORE_PACKAGE_NAME))
        assertTrue(AddonIpcContract.shouldIgnorePackage(AddonIpcContract.ADDON_PACKAGE_NAME))
        assertFalse(AddonIpcContract.shouldIgnorePackage("com.whatsapp"))
    }

    @Test
    fun `serializes event and rejects oversized or malformed commands`() {
        val msg = NotificationEventMessage(
            action = "POST",
            sbnKey = "0|com.whatsapp|1|null|1000",
            appName = "WhatsApp",
            packageName = "com.whatsapp",
            title = "Alex",
            body = "Ready?",
            timestampMs = 1711800000000L,
            canReply = true
        )
        val encoded = AddonIpcContract.encodeEvent(msg)
        assertTrue(encoded.contains("\"WhatsApp\""))

        val validCmd = """{"msgType":"DEVICE_COMMAND","command":"replyToNotification","sbnKey":"k1","replyText":"Yes","timestampMs":1711800000000}"""
        val decoded = AddonIpcContract.decodeCommand(validCmd)
        assertEquals("replyToNotification", decoded?.command)
        assertEquals("Yes", decoded?.replyText)

        assertNull(AddonIpcContract.decodeCommand(null))
        assertNull(AddonIpcContract.decodeCommand("not-json"))
        val huge = "x".repeat(300 * 1024)
        assertNull(AddonIpcContract.decodeCommand(huge))
    }
}
