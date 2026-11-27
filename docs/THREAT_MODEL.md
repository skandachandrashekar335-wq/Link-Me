# Threat Model & Security Controls: Link-Me

## 1. Assets & Trust Boundaries

| Asset | Sensitivity | Storage & Protection |
| :--- | :--- | :--- |
| Device TLS Private Key | Critical | macOS Keychain (non-exportable identity) / Android Keystore (`AndroidKeyStore`, hardware-backed where available) |
| Pinned Peer Certificate Fingerprint (SPKI SHA-256) | Critical | Keychain / Encrypted app store; written only after 6-digit SAS confirmation |
| `ble_beacon_key` (256-bit) | High | Derived via HKDF-SHA256 during Curve25519 QR pairing; stored in Keychain / Keystore |
| Clipboard Contents | High | Transmitted only over authenticated mTLS 1.3; password-manager entries dropped pre-read |
| Notification Content & Inline Reply | High | Transmitted only over mTLS 1.3; optional redaction filter |
| Transferred Files | High | Written only inside canonical staging dir after strict filename sanitization & SHA-256 verification |

---

## 2. Adversary Model

1. **Passive/Active LAN Attacker:** Can sniff mDNS/TCP traffic, inject malformed binary frames, replay captured packets, or present rogue TLS certificates on the local Wi-Fi network.
2. **BLE Observer / Spoofer:** Can capture BLE advertisements within radio range and replay them later to force unnecessary Wi-Fi wake-ups (battery DoS) or track the user's device across time via static identifiers.
3. **Malicious/Compromised Sender Input:** Crafts `FILE_OFFER` payloads containing `../` traversal sequences, NUL bytes, or oversized `Payload Length` headers to escape the Downloads directory or trigger integer overflow / OOM crashes.
4. **Local Clipboard Leakage:** Password managers (1Password, Bitwarden, KeePassXC, Apple Passwords) place credentials on the system clipboard; leaking these to another device or logs violates confidentiality.

---

## 3. Mitigations & Security Controls

### C1. mTLS 1.3 with macOS Keychain & Android Keystore Certificate Pinning
* Both endpoints require **TLS 1.3** exclusively (`TLSv1.2` and below are disabled) with mandatory client certificate authentication (`needClientAuth = true` / `sec_protocol_options_set_peer_authentication_required(..., true)`).
* System CA trust stores are **bypassed completely**. During TLS handshake verification, the custom verifier extracts the peer's X.509 leaf `SubjectPublicKeyInfo` (SPKI), computes `SHA-256(SPKI)`, and performs a constant-time comparison against the Keychain/Keystore-pinned hash bound to that paired `deviceId`.
* Unpinned or mismatched certificates terminate the TLS handshake immediately before any `WireFrame` byte is read.

### C2. Out-of-Band QR Curve25519 Pairing & 6-Digit SAS
* Initial pairing does not trust-on-first-use (TOFU) blindly over Wi-Fi.
* An out-of-band visual channel (QR code scanned by Android camera) delivers the initiator's ephemeral Curve25519 public key (`pk_A`), `deviceId_A`, and 128-bit `pairNonce`.
* Both sides perform `X25519(sk, pk_peer)` and derive:
  ```
  PRK = HKDF-Extract(salt = pairNonce, IKM = sharedSecret)
  SAS_bytes = HKDF-Expand(PRK, info = "linkme-sas-v1" || pk_A || pk_B, L = 4)
  SAS_code = (uint32_be(SAS_bytes) % 1_000_000) formatted as "%06d"
  ble_beacon_key = HKDF-Expand(PRK, info = "linkme-ble-v1" || pk_A || pk_B, L = 32)
  ```
* Users visually verify the 6-digit SAS on both devices before committing certificate pins, defeating active MITM substitution during the bootstrap exchange.

### C3. 15-Minute Rotating BLE HMAC Tokens
* Static BLE UUIDs or device IDs enable passive physical tracking and replay wake-ups.
* BLE wake advertisements carry: `epoch_hint (1B)` + `nonce (6B)` + `HMAC_trunc (8B)`.
* Token computation:
  ```
  bucket = floor(unix_timestamp_seconds / 900)   // 15-minute rotation window
  mac = HMAC-SHA256(key = ble_beacon_key, data = uint64_be(bucket) || nonce)
  token = mac[0..<8]
  ```
* Receivers verify against `bucket`, `bucket - 1`, and `bucket + 1` (clock skew tolerance) using constant-time equality and maintain a seen-`nonce` cache for the active window to block immediate BLE replays.

### C4. Binary Framing Bounds & Anti-Replay `SequenceID`
* Every frame enforces `Magic == 0x414D`, `Version == 0x01`, `MsgType in 0x01...0x09`, and `PayloadLength <= 16_777_216` (16 MiB) before buffer allocation.
* Each connection initializes `lastAcceptedSeq = 0`. Incoming frames MUST satisfy `frame.sequenceId > lastAcceptedSeq` (strictly monotonic). Any duplicate or regressive `SequenceID` closes the session as a replay violation.

### C5. Path-Traversal Filename Sanitization
* `FILE_OFFER` filenames are untrusted input. Before displaying a file offer or opening any file descriptor, `FilenameSanitizer` enforces:
  1. Reject empty strings, NUL bytes (`\0`), or ASCII control characters (`0x00–0x1F`, `0x7F`).
  2. Reject any path separator (`/`, `\`), drive letters (`C:`), or traversal segments (`..`, `.`).
  3. Strip leading dots (preventing hidden config overwrites like `.bashrc` or `.ssh`) and limit length to 255 UTF-8 bytes.
  4. Resolve the target path inside the dedicated staging/receive directory and verify `canonicalTarget.startsWith(canonicalRoot + separator)`.

### C6. Password-Manager & Sensitive Clipboard Filtering
* **Android:** Before reading or transmitting clipboard text, `ClipboardSyncManager` inspects `ClipData.description.extras` for `ClipDescription.EXTRA_IS_SENSITIVE == true` (Android 13+/API 33+) as well as legacy concealed indicators (`android.content.extra.IS_SENSITIVE`, `"com.agilebits.onepassword"`, `"com.x8bit.bitwarden"`). Sensitive clips are skipped immediately.
* **macOS:** `ClipboardMonitor` inspects `NSPasteboardItem.types` on the general pasteboard. Any item containing `org.nspasteboard.ConcealedType`, `org.nspasteboard.TransientType`, or `org.nspasteboard.AutoGeneratedType` is immediately ignored and never serialized into `CLIPBOARD_SYNC`.
* **No Logging of Secrets:** Clipboard text and notification bodies are redacted from diagnostic logs (`<redacted: N bytes>`).
