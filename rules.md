# DisasterConnect Coding Rules & Standards

This document outlines the coding standards and project rules for the DisasterConnect Kotlin project.

## 1. Project Overview
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Map Engine**: MapLibre GL Android SDK
- **Location Services**: Google Play Services Fused Location Provider (`FusedLocationProviderClient`)
- **Build System**: Gradle with Kotlin DSL (`.gradle.kts`)

## 2. Formatting & Style Guidelines
A unique visual style is maintained in this project to enhance readability:

- **Vertical Spacing**: Use aggressive vertical spacing. Almost every logical line or parameter in a function call should be on a new line with a blank line separating it from the next.
- **Section Headers**: Use formal comment blocks to separate major sections of a file:
  ```kotlin
  // =========================================================
  // MAJOR SECTION NAME
  // =========================================================
  ```
- **Sub-section Headers**: Use dashed comment blocks for smaller logical groupings:
  ```kotlin
  // ---------------------------------------------------------
  // Sub-section Name
  // ---------------------------------------------------------
  ```
- **Indentation**: Standard 4-space indentation.

## 3. UI Development (Compose)
- **Activity Structure**: Activities should handle permission requests and high-level navigation.
- **State Management**: Use `mutableStateOf` with `by` delegates in Activities for UI reactivity.
- **Theme**: Always wrap Compose UIs in `DisasterConnectTheme`.
- **Naming**: Composable functions should use PascalCase (e.g., `DisasterConnectHome`).

## 4. Location & Permissions
- **Fused Location**: Always use `FusedLocationProviderClient` for location updates.
- **Permission Flow**: 
    - Use `ActivityResultContracts.RequestMultiplePermissions()` for location access.
    - Check both `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`.
    - Provide a clear `PermissionScreen` when access is denied.

## 5. Network & Connectivity
- **Connectivity Monitoring**: Use `ConnectivityManager` to check for active networks.
- **Validation**: Ensure `NET_CAPABILITY_VALIDATED` is checked to confirm actual internet access, not just a connection to a router.

## 6. Maps (MapLibre)
- **Initialization**: Call `MapLibre.getInstance(this)` before creating `MapView`.
- **Lifecycle**: Explicitly forward all Activity lifecycle methods (`onStart`, `onResume`, `onPause`, etc.) to the `MapView` instance.

## 7. Resource Management
- **Strings**: User-facing text should be descriptive and clear, reflecting the emergency nature of the app.
- **Icons**: Use Emoji symbols (e.g., 📍, ⚠️) sparingly for visual cues in the UI where appropriate.

## 8. Change Discipline (Most Important)
- **Minimal diffs**: Change only what the task requires. Do not reformat, rename, reorder imports, or "clean up" unrelated code.
- **No silent refactors**: Do not move code between files or split large files (e.g., `MainActivity.kt`) unless explicitly asked.
- **Keep public signatures**: Do not change existing function/class signatures, DB columns, BLE UUIDs, or SharedPreferences keys without agreement.
- **Match surrounding style**: New code in a file follows that file's existing style. The `network/` and `ble/` packages are more compact than the activities; do not reformat one to match the other.
- **Dependencies**: Do not add libraries or bump versions unless required by the task. Prefer what is already in `app/build.gradle.kts` (Room, WorkManager, Retrofit, OkHttp, Coroutines, MapLibre, Play Services Location).
- **Ask before**: deleting files, changing the Room schema, changing the BLE protocol, or touching `AndroidManifest.xml` permissions.
- **Verify**: After changes, run `./gradlew :app:testDebugUnitTest` (and `:app:assembleDebug` for non-trivial changes).

## 9. Project Structure
```
app/src/main/java/com/example/disasterconnect/
├── MainActivity.kt            # Dashboard: permissions, GPS, network status, SOS creation + dialog, home UI
├── MapActivity.kt             # MapLibre map, sample shelters, nearest-shelter button (View-based, not Compose)
├── NearbyDevicesActivity.kt   # BLE scan UI, node list, GATT connect, MESSAGE button
├── ChatActivity.kt            # Conversation with one node, or inbox of all messages (peer = null)
├── VerificationActivity.kt    # Safety-code comparison with one directly connected phone
├── ble/
│   ├── DisasterConnectGatt.kt        # Protocol UUIDs, HELLO/ACK constants, GATT service definition
│   ├── DisasterConnectGattServer.kt  # Peripheral side: serves node id, sends HELLO, accepts ACK
│   ├── DisasterConnectGattClient.kt  # Central side: connect, read node id, HELLO → ACK handshake
│   ├── BleAdvertiser.kt              # Process-wide BLE advertisement of this node id
│   └── BleConnectionManager.kt       # Process-wide owner of node id, GATT server/client, links, send()
├── messaging/                 # Pure-Kotlin protocol + rules (unit-tested), plus the coordinator
│   ├── MessagePacket.kt              # DataPacket / AckPacket binary codec
│   ├── PacketFraming.kt              # MTU frames, reassembly, one-frame-in-flight queue
│   ├── MessagingEngine.kt            # Dedup, TTL, ACK, retry/backoff, SOS relay rules (+ Room store)
│   ├── ReceiptCodec.kt               # Signed delivery receipts: canonical bytes, digest, strict codec, verify
│   ├── ReceiptCrypto.kt              # ReceiptSigner/Verifier, strict ECDSA P-256 keys and signatures
│   └── MessagingCoordinator.kt       # Process-wide glue: links ↔ engine, auto-connect, ACK timeouts
├── network/
│   ├── NearbyNetworkRepository.kt    # Process-wide BLE scanner + observable nearby node list
│   ├── NetworkDecisionEngine.kt      # Scores LinkCandidates (BLE / WIFI_DIRECT / INTERNET)
│   └── OfflineRelayQueue.kt          # Store-and-forward queue on top of EmergencyMessageDao
├── data/
│   ├── AppDatabase.kt                # Room DB (version 5, MIGRATION_1_2 … MIGRATION_4_5)
│   ├── TrustedKey.kt / TrustedKeyDao.kt  # Other phones' public keys pinned to node IDs (SEEN only so far)
│   ├── SosPacket.kt / SosDao.kt      # Rich SOS snapshot (battery, cellular, device info)
│   └── EmergencyMessage.kt / EmergencyMessageDao.kt  # PRD message model, priority, delivery state
├── security/
│   ├── DeviceIdentity.kt             # This phone's Keystore signing key (dc_identity_v1)
│   ├── TrustedKeyStore.kt            # Validated, never-replacing access to trusted_keys; confirmVerified
│   ├── KeyExchange.kt                # Direct-link KEY exchange, link keys, conflicts, stale-safe confirmation
│   ├── KeyPayload.kt                 # KEY payload codec and the 12-digit SafetyCode
│   ├── ContactVerification.kt        # Trust labels and what the verification screen may show
│   └── P256PublicKeys.kt             # Canonical P-256 public key encoding
└── ui/theme/                  # DisasterConnectTheme
app/src/test/.../network/NetworkDecisionEngineTest.kt  # Unit tests for the decision engine
```

## 10. PRD Alignment
Source documents: *Adaptive Heterogeneous Disaster Communication Network – PRD v1.0* and *System Architecture & Infrastructure*.

| PRD component | Code location |
|---|---|
| Discovery Manager | `network/NearbyNetworkRepository`, `NearbyDevicesActivity` |
| Network Decision / Selection Engine | `network/NetworkDecisionEngine` |
| Routing & Relay Engine | `network/OfflineRelayQueue` |
| Local Data Store | `data/` (Room) |
| Peer link (BLE) | `ble/` |
| Map Manager | `MapActivity` |
| Offline BLE messaging (FR-04–07, 13–15, 17) | `messaging/`, `ChatActivity` |
| Gateway Manager, Wi-Fi P2P, server sync | **Not implemented yet** |

**Rules for new features:**
- Every feature should map to a PRD Functional Requirement (FR-01 … FR-17) or Development Phase. Mention the FR in the PR/commit message.
- **Offline-first**: local communication must never depend on Internet or cloud access.
- **Out of MVP scope** (do not build): voice/video, large files, satellite, complex maps, government integrations.
- Android first; `minSdk = 26`, `targetSdk = 36`. Always handle API-level differences (e.g., BLE permissions on API 31+, GATT write APIs on API 33+).

## 11. Message Model & Delivery Rules
- Use `EmergencyMessage` for anything relayed between devices. `SosPacket` is the local rich snapshot; an SOS creates **both**.
- **Priorities**: only `MessagePriority.CRITICAL / HIGH / NORMAL` (FR-14). SOS is always `CRITICAL`.
- **States**: only `DeliveryState` constants (FR-17): `CREATED → QUEUED → FORWARDED → GATEWAY_REACHED → SERVER_RECEIVED → DELIVERED`, or `EXPIRED`. Do not invent new states without updating the PRD mapping.
  - Added for BLE messaging: `SENT` (all frames accepted, waiting for the receiver's ACK) and `FAILED` (retry limit reached or rejected; user can retry). UI shows `QUEUED` as "Pending".
  - A direct message becomes `DELIVERED` **only** when an ACK arrives from its receiver over the link to that receiver. Never on write success.
  - Broadcasts (`receiverId = null`, e.g. SOS) are handed to each peer once (`message_handoffs` table) and become `FORWARDED`; received broadcasts are relayed up to `MessagingEngine.DEFAULT_MAX_HOPS`.
  - **Direct messages through relays (multi-hop):** a direct message (A → C) may be handed to a relay (B) when C is not linked. B stores a *relay copy* (`isIncoming = true`, `receiverId = C`, same `messageId` and `senderId`), hidden from B's own inbox, and forwards it when C (or another relay) is linked.
    - Sender A shows `FORWARDED` ("Passed to nearby phone") when a relay ACKs. A relay ACK is **hop-level only** and never means C received the message.
    - `DELIVERED` still means only: ACK from the final recipient over a direct link. The current ACK format cannot carry C's ACK back through relays, so A sees `DELIVERED` only if it later reaches C directly. End-to-end receipts need a protocol extension; do not add one without agreement.
    - Limits: TTL, `DEFAULT_MAX_HOPS`, never back to the sender or the peer it came from (`message_handoffs`), and at most `MessagingEngine.DEFAULT_MAX_RELAY_COPIES` relay handoffs per phone per message. The final recipient can always receive it.
    - A relay's `REJECTED` (e.g. hop limit, or an older app version) only rules out that relay; `FAILED` still requires the recipient itself to reject, or the retry limit.
  - **Signed delivery receipts (implemented in `MessagingEngine`; personal direct messages only — `RECEIPTABLE_TYPES` = TEXT, STATUS, LOCATION):**
    - For a recipient whose public key the user has **verified**, `DELIVERED` will require a valid signed receipt from that recipient, for direct *and* multi-hop messages. A plain BLE ACK is then hop-level only and never verified end-to-end delivery.
    - Unverified or legacy recipients keep the current ACK behaviour, but the UI must show that delivery as **unverified**, distinct from cryptographically verified delivery.
    - Only the final recipient creates a receipt, once, for a newly stored direct message. Never for a receipt, a key message, a broadcast, or an SOS (SOS behaviour is unchanged).
    - Receipts and key messages are never shown as chat messages, never notified, and never counted as waiting-to-send.
    - Receipt transport TTL never exceeds the original message's remaining TTL; normal TTL, hop and relay-copy limits apply. A receipt's `messageId` is content-addressed (`DeliveryReceiptCodec.receiptMessageId`).
    - A late valid receipt may move a `FAILED` or `EXPIRED` message to `DELIVERED` (the recipient really has it); applying a receipt twice changes nothing; an invalid receipt never changes any state.
    - `deliveryProof` (column, not a state): `NONE`, `LINK_ACK` (the recipient's own BLE ACK, unauthenticated) or `SIGNED_RECEIPT`. Verified recipient + ACK → `FORWARDED` + `LINK_ACK` ("Reached recipient · awaiting verified receipt"; recorded as a handoff, never sent again). Unverified recipient + ACK → `DELIVERED` + `LINK_ACK` ("Delivered · unverified"). Valid receipt from the VERIFIED, non-conflicting key → `DELIVERED` + `SIGNED_RECEIPT` ("Delivered · verified by recipient"). The UI shows "verified" only from the stored proof.
    - The recipient stores a new message and its receipt in one transaction (`insertWithReceipt`). Without a usable Keystore key it stores the message alone and `recoverReceipts` signs later; it never signs with any other key.
    - The sender rebuilds the signed bytes from its own outgoing row and checks the envelope sender is the original recipient. Receipts are never stored at the sender; invalid or unverifiable ones are answered `REJECTED` so relays stop.
    - Relays store receipts unchanged (`receiptFor` = original messageId), with the normal hop/TTL/handoff/relay-copy rules plus: priority must be NORMAL, no location, lifetime ≤ 24 h, receipt id must equal the hash of its content, at most `MAX_RECEIPTS_PER_MESSAGE` per original and `MAX_RELAY_RECEIPTS` in total.
    - A receipt sent without any ACK before the ACK timeout records a handoff for that peer: each phone gets one try per receipt (older apps drop type 4 silently). Message retry timing is unchanged.
- **Direction**: `isIncoming` marks received messages; `currentRelayId` is the peer it came from / was last sent to.
- **IDs**: every message has a globally unique `messageId` (UUID). Deduplication relies on the unique index on `messageId` + `OnConflictStrategy.IGNORE` (FR-13). Never regenerate the ID when relaying.
- **TTL**: every message must set `expiresAt` (SOS and text: 24 h). Expired messages are never forwarded (FR-05). On the wire TTL is the *remaining* seconds, so phone clocks need not agree.
- **Hop count**: increment by exactly 1 per forward; keep `currentRelayId` updated.
- **Queue ordering**: CRITICAL → HIGH → NORMAL, then oldest first. Keep it in the DAO query.

## 12. Network Decision Engine Rules
- Keep `NetworkDecisionEngine` **pure Kotlin** (no Android imports) so it stays unit-testable.
- CRITICAL traffic weights reliability and gateway reachability; NORMAL traffic gives weight to battery (PRD §6).
- Any scoring change must keep `NetworkDecisionEngineTest` passing and add a test for the new behaviour.
- A gateway counts as `GATEWAY_REACHED` only if it is **trusted and has Internet**. Never auto-trust arbitrary nearby nodes (PRD §7).

## 13. BLE Protocol Rules
- Service UUID `c7f9f1b5-0ce1-4dbe-9c75-c2d7dc5e9b0a` and characteristic UUIDs in `DisasterConnectGatt` are a **wire protocol**. Do not change them; changing them breaks compatibility with other phones.
- Advertisement service data = `[PROTOCOL_VERSION (1 byte)] + [3-byte node id]`. Node IDs look like `DC-XXXXXX`. If the format changes, bump `PROTOCOL_VERSION`.
- Handshake: client requests MTU 185 (falls back to 23) → reads node info → subscribes to message characteristic → server notifies `HELLO` → client writes `ACK|<nodeId>`.
- After the handshake the same message characteristic carries **data frames**: client → server as writes with response, server → client as notifications. Frames start with `0xFE` (never valid UTF-8, so they cannot clash with `HELLO`/`ACK|`). Header: magic, transferId(2), index, count. One frame in flight per link.
- Packet bytes are defined in `messaging/MessagePacket.kt` (`MessagePacketCodec.VERSION`). Bump the version for incompatible changes; decoders must return null, never throw.
- **Message type indexes are wire values:** `0 TEXT, 1 SOS, 2 STATUS, 3 LOCATION`, plus approved reservations `4 RECEIPT, 5 KEY` (`MessagePacketCodec.TYPE_INDEX_*`). Never reorder or reuse an index; append only.
  - Indexes 4 (`RECEIPT`, handled by `MessagingEngine`) and 5 (`KEY`, handled by `KeyExchange`) are active. Older builds drop them silently (no ACK), so receipts get one try per peer instead of retrying forever.
  - Adding them does not change the packet layout or the ACK format, so `MessagePacketCodec.VERSION` stays `1`. Bumping it would make older phones drop all traffic.
  - The receipt payload (Base64 text in a RECEIPT message) and its signed bytes are defined in `DeliveryReceiptCodec`. Change them only with a new receipt version and domain string.
  - **`KEY` (index 5) is active.** Payload (Base64): `version(1)=1, nodeId (u8 length, DC-XXXXXX), keyLength(1)=91, publicKey(91), SHA-256 fingerprint(32)` (`KeyPayloadCodec`). Accepted only with hopCount 0, receiverId = this phone, senderId = the link peer = the payload node ID. `MessagingCoordinator` hands KEY packets to `KeyExchange` and never to `MessagingEngine`; the engine refuses KEY/RECEIPT packets as a second guard. They are never stored, shown, notified, ACKed or relayed.
  - A phone sends its KEY once per peer per app run (older apps drop it silently), again only after a failed write or an explicit user request from the verification screen.
- Advertising and the GATT server are process-wide (`BleConnectionManager.startNode`). Activities must not stop them in `onPause`.
- Use the shared singletons (`NearbyNetworkRepository`, `BleConnectionManager`). Do not start a second scanner or GATT server from another screen.
- Always catch `SecurityException` around Bluetooth calls; permissions can be revoked at runtime.

## 14. Room / Data Rules
- Any entity change requires: bump `AppDatabase.version`, add a `Migration`, register it in `addMigrations(...)`. **Never use `fallbackToDestructiveMigration()`**; it would lose queued SOS messages.
- DB access only off the main thread (`Dispatchers.IO` / suspend DAO functions).
- Store as little location data as possible (PRD §9 data minimisation).
- Add every migration to `AppDatabase.MIGRATIONS` (used by the app and by `AppDatabaseMigrationTest`), and extend that instrumented test with the previous version's real schema.

## 15. Security Rules (FR-16)
- Do not log message contents, locations, or node IDs at INFO level in release builds.
- Sensitive payloads must be encrypted before leaving the device once the security phase starts; design new transfer code so that an encryption step can be inserted before transmission.
- `usesCleartextTraffic="true"` is for development only. Use HTTPS for any real server endpoint.
- **Delivery receipt cryptography** (`messaging/ReceiptCrypto.kt`, `ReceiptCodec.kt`):
  - ECDSA P-256 with SHA-256 only, through the platform `java.security` APIs (no crypto libraries). Keys must be the canonical 91-byte SPKI of a point on P-256; signatures must be strict minimal DER with low S. Reject everything else.
  - Signed bytes are domain-separated (`"DisasterConnect/receipt/v1\0"`) and rebuilt by the verifier from its **own** stored message, never taken from the wire.
  - **Never trust a node ID or a public key because it arrived over BLE** or matches a claimed node ID. Only a key the user explicitly verified for that node ID (code comparison or QR, later phase) may authorise a receipt.
  - This phone's signing key lives in the Android Keystore (`security/DeviceIdentity`, alias `dc_identity_v1`): P-256, sign-only, SHA-256, no user authentication, StrongBox when available. Never export, log, delete or overwrite it from app code. A lost key is reported (`IdentityStatus.Lost`) and only replaced through the explicit `replaceLostIdentity()`.
  - The node ID is a routing label, not proof of identity; the signing key is separate from it.
  - Other phones' keys go only through `TrustedKeyStore`: they enter as `SEEN`, a node ID keeps its first key (a different key is a conflict, never stored, shown as "Key changed").
  - `VERIFIED` is set only by `TrustedKeyStore.confirmVerified`, reached only from the user's "Codes match" tap (`VerificationActivity` → `MessagingCoordinator.confirmVerification` → `KeyExchange`). It requires the peer to be linked now, its key on this link to equal the stored key and the fingerprint the code was made from; the DAO update is a single conditional statement (`WHERE nodeId AND fingerprint AND trust = 'SEEN'`). Never call it from BLE events, handshakes or startup. Verification is per phone: A verifying B never changes B's record of A.
  - Safety code: `SHA-256("DisasterConnect/safety-code/v1\0" || min(partyA, partyB) || max(partyA, partyB))`, party = `u8 len || nodeId || publicKey`, first 8 bytes unsigned mod 10^12, shown as `123 456 789 012` (`SafetyCode`).
  - **SOS is independent of all of this.** Key exchange, trust state and verification are for personal messages only. Never add identity, trust or verification checks to SOS creation, receipt, storage, deduplication or relay (`SosIndependenceTest`). SOS status stays separate from personal-message delivery status.
  - **Backups:** `disaster_connect_ble.xml` (node ID) and `disaster_connect_identity.xml` (identity key record) are excluded from Auto Backup and device transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). Keep them excluded. A restored or reinstalled phone therefore gets a new node ID and a new key.
  - Signatures prevent forgery and tampering only. They do not stop relays from dropping, delaying or flooding receipts; those are limited by TTL, hop limits, relay-copy limits and storage caps.

## 16. Testing
- Pure logic (decision engine, queue rules, TTL, dedup) needs JVM unit tests in `app/src/test`.
- Name tests by behaviour, e.g. `criticalSosPrefersTrustedInternetGateway`.
- BLE behaviour needs two real devices. Note in the PR when something was only verified on one device.
