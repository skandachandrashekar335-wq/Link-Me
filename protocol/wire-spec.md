# Link-Me Binary Wire Specification (`v1` / `0x01`–`0x0B`)

See [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) and [`protocol/schema.json`](./schema.json) for full payload schemas and security invariants.

## Frame Envelope (16-Byte Fixed Header + Variable Payload)
- `0..1`: Magic `0x41 0x4D` (`"AM"`)
- `2`: Version `0x01`
- `3`: `MsgType` (`0x01`–`0x0B`)
- `4..7`: `PayloadLength` (`uint32 BE`, max `1,048,576` B)
- `8..15`: `SequenceID` (`uint64 BE`, monotonic 64-bit sliding `ReplayGuard`)
- `16..`: Payload (UTF-8 JSON or raw binary `FILE_CHUNK`)

## Message Types
- `0x01` `PAIR_HELLO`
- `0x02` `PING_TELEMETRY`
- `0x03` `WAKE_WIFI`
- `0x04` `CLIPBOARD_SYNC`
- `0x05` `FILE_OFFER`
- `0x06` `FILE_RESPONSE`
- `0x07` `FILE_CHUNK`
- `0x08` `NOTIFICATION_EVENT`
- `0x09` `DEVICE_COMMAND`
- `0x0A` `MEDIA_CONTROL`: `{"action":"play_pause"|"next"|"prev"|"vol_up"|"vol_down"|"mute"}`
- `0x0B` `OPEN_URL`: `{"url":"https://..."}` (strictly `http://` or `https://` validated by `UrlSanitizer`)
