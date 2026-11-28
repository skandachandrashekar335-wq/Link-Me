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

    @Test
    fun `decodes remote input gestures and clamps normalized coordinates`() {
        val tapJson = """{"msgType":"REMOTE_INPUT","type":"tap","x":0.5,"y":0.25}"""
        val decoded = AddonIpcContract.decodeRemoteInput(tapJson)
        assertEquals("tap", decoded?.type)
        assertEquals(0.5, decoded?.x ?: 0.0, 0.0001)

        val enterMsg = AddonIpcContract.decodeRemoteInput("""{"msgType":"REMOTE_INPUT","type":"key","action":"enter"}""")
        assertEquals("enter", enterMsg?.action)

        val dpadMsg = AddonIpcContract.decodeRemoteInput("""{"msgType":"REMOTE_INPUT","type":"key","action":"dpad_down"}""")
        assertEquals("dpad_down", dpadMsg?.action)

        val textMsg = AddonIpcContract.decodeRemoteInput("""{"msgType":"REMOTE_INPUT","type":"text","text":"hello link-me"}""")
        assertEquals("text", textMsg?.type)
        assertEquals("hello link-me", textMsg?.text)

        assertNull(AddonIpcContract.decodeRemoteInput(null))
        assertNull(AddonIpcContract.decodeRemoteInput("invalid"))
        assertNull(AddonIpcContract.decodeRemoteInput("x".repeat(300 * 1024)))

        assertEquals(540f, AddonRemoteInputMapper.toPixel(0.5, 1080), 0.1f)
        assertEquals(0f, AddonRemoteInputMapper.toPixel(-0.2, 1080), 0.1f)
        assertEquals(2399f, AddonRemoteInputMapper.toPixel(1.2, 2400), 0.1f)
        assertEquals("hell", AddonRemoteInputMapper.applyBackspace("hello"))
        assertEquals("", AddonRemoteInputMapper.applyBackspace(""))
        assertEquals("hello world", AddonRemoteInputMapper.applyAppendText("hello ", "world"))
    }
}
