# Binary Wire & Control Protocol: Link-Me v1 (`0x01`)

All communication after the TLS 1.3 mTLS handshake uses framed binary messages over a single ordered byte stream. Multi-byte integers are **big-endian (network byte order)**.

---

## 1. Binary Wire Frame Layout

Every frame has a fixed **16-byte header** followed immediately by `Payload Length` bytes:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     Magic (0x414D = "AM")     |  Version (01) |   MsgType     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Payload Length (uint32 BE)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                    SequenceID (uint64 BE)                     +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Payload (0 .. N bytes)                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Header Field Specification

| Offset | Size | Field | Description |
| :--- | :--- | :--- | :--- |
| `0` | `2B` | **Magic** | Constant `0x41 0x4D` (`"AM"`). Any other value MUST abort frame parsing and close the socket. |
| `2` | `1B` | **Version** | Protocol version. Must equal `0x01`. Unsupported versions MUST be rejected. |
| `3` | `1B` | **MsgType** | Message type enum (`0x01`–`0x09`). Unknown types MUST be rejected. |
| `4` | `4B` | **Payload Length BE** | Unsigned 32-bit big-endian payload byte count. Hard maximum: `1,048,576` bytes (`1 MB` control cap; `65,536` bytes per `FILE_CHUNK` data slice). |
| `8` | `8B` | **SequenceID BE** | Unsigned 64-bit big-endian monotonic counter starting at `1` per TLS session, validated via a 64-bit sliding `ReplayGuard`. |
| `16` | `N B` | **Payload** | UTF-8 JSON object conforming to [`protocol/schema.json`](../protocol/schema.json) (for control messages), or binary chunk record (for `0x07 FILE_CHUNK`). |

---

## 2. Message Type Enums (`0x01`–`0x09`)

| Code | Name | Direction | Payload Format | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `0x01` | `PAIR_HELLO` | Both | JSON | Session & pairing identity exchange (device ID, ephemeral Curve25519 pubkey, SPKI fingerprint, protocol capabilities). |
| `0x02` | `PING_TELEMETRY` | Both | JSON | Battery level, charging state, battery-saver flag, transport state (`BLE_STANDBY` / `WIFI_TURBO` / bridge state). |
| `0x03` | `WAKE_WIFI` | Both | JSON | Wi-Fi endpoint wake announcement (service port, IPv4/IPv6 hints, rotating BLE token reference, wake reason). |
| `0x04` | `CLIPBOARD_SYNC` | Both | JSON | Loop-safe clipboard update (`originDeviceId`, `clipboardEpoch`, `contentSha256`, `mimeType`, `text` / `imageBase64`, `isSensitive`). |
| `0x05` | `FILE_OFFER` | Sender -> Receiver | JSON | Offer to send a file (`transferId`, sanitized `filename`, `byteSize`, `sha256`, `chunkSize`, `totalChunks`, `mimeType`). |
| `0x06` | `FILE_RESPONSE` | Receiver -> Sender | JSON | Accept/decline/resume response (`transferId`, `accepted`, `resumeChunkIndex`, `reason`). |
| `0x07` | `FILE_CHUNK` | Sender -> Receiver | Binary | Raw chunk payload: `16B transferUUID` + `4B chunkIndex BE` + `4B dataLength BE` + `32B chunkSHA256` + `dataLength` bytes (`<= 65,536` B default, `<= 262,144` B max). |
| `0x08` | `NOTIFICATION_EVENT` | Android -> macOS | JSON | Mirrored notification event (`action: "POST" \| "DISMISS"`, `sbnKey`, `appName`, `packageName`, `title`, `body`, `timestampMs`, `canReply`). |
| `0x09` | `DEVICE_COMMAND` | Both | JSON | Remote command (`command: "replyToNotification" \| "dismissNotification" \| "ringPhone" \| "stopRing"`, `sbnKey`, `replyText`, `durationSeconds`, `timestampMs`). |

---

## 3. Binary Payload Layout for `0x07 FILE_CHUNK`

| Offset (in Payload) | Size | Field |
| :--- | :--- | :--- |
| `0` | `16B` | `transferUUID` (raw 16-byte UUID matching `FILE_OFFER.transferId`) |
| `16` | `4B` | `chunkIndex` (`uint32 BE`, `0 <= chunkIndex < totalChunks`) |
| `20` | `4B` | `dataLength` (`uint32 BE`, must equal `Payload Length - 56`) |
| `24` | `32B` | `chunkSHA256` (SHA-256 digest of the `dataLength` raw bytes) |
| `56` | `dataLength B` | Raw file chunk bytes (`65,536` bytes per streaming chunk) |
