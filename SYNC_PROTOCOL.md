# VaultPass LAN sync protocol

VaultPass for Android and VaultPass Desktop sync directly over the local network. There is no server, no account and no cloud relay: the two devices find each other on the same Wi-Fi or LAN, prove they were paired, and exchange encrypted records over one TCP connection.

This file describes protocol v2 as implemented in both apps. The same file is kept in both repositories (`docs/SYNC_PROTOCOL.md` on Android, `SYNC_PROTOCOL.md` on desktop); keep the two copies identical.

## 1. Where the code lives

| Part | Android | Desktop |
|---|---|---|
| Shared core: crypto, handshake, framing, beacons, records, merge plans | `app/src/main/java/com/vaultpass/synccore/` | `src/main/kotlin/com/vaultpass/synccore/` |
| Connection, message rules, pairing | `network/sync/LanSocketTransport.kt` | `data/sync/LanSocketTransport.kt` |
| Discovery | `network/sync/LanDiscoveryManager.kt` | `data/sync/LanDiscoveryManager.kt` |
| Messages | `domain/sync/models/SyncNetworkFrames.kt` | `domain/sync/models/SyncNetworkFrames.kt` |
| Session, review, approval | `ui/sync/LanSyncViewModel.kt` | `ui/viewmodels/SyncViewModel.kt` |
| Applying a merge plan | `domain/sync/diff/SyncMergeExecutor.kt` | `domain/sync/diff/SyncMergeExecutor.kt` |

The shared core (`com.vaultpass.synccore`) is byte-identical in both apps. Change it in one app, copy it to the other, and confirm the checksums match. The message classes are not shared code, but their `@SerialName`s, fields and defaults must match exactly.

## 2. Network use

| Port | Protocol | Used for |
|---|---|---|
| 53852 | UDP broadcast | Discovery beacons and `PING` requests |
| 53853 | TCP | Pairing and sync connections |

Both apps listen only while their Sync screen is open, and the desktop also listens while it shows a pairing QR code. Each app serves one connection at a time and turns other devices away while one is in progress.

The desktop can add two Windows Firewall rules, for inbound TCP 53853 and UDP 53852, limited to the **Private** network profile. It asks for administrator rights to do so and removes older rules that applied to every profile.

## 3. Pairing

Pairing happens once per phone and desktop. It creates a 32-byte **pair key** that both devices store and later use to find and authenticate each other.

1. The desktop shows a QR code:
   ```
   vaultpass://pair?v=2&deviceId=<id>&name=<url-encoded name>&secret=<64 hex chars>&ip=<primary ip>&ips=<ip,ip,...>&port=53853
   ```
   `secret` is a fresh random 32-byte value. It never goes on the network: only the phone's camera sees it.
2. The phone tries each IP address from the code until one connects, then runs the handshake (section 5) in `pair` mode with `secret` as the shared secret. A phone that scans a v1 code (no `v=2`, with a `token` or `pairingToken`) is told to update the desktop.
3. The phone sends `PairingInfo {deviceId, deviceName}` inside the encrypted channel.
4. The desktop shows the phone's name and asks its user to accept. It answers `PairingAcceptance {deviceId, isAccepted}`.
5. On acceptance both sides store the pair key that came out of the handshake, and the desktop forgets the QR secret, so one code pairs one phone. The phone checks that the answer names the device from the QR code, stores the desktop, and hangs up.

Pair keys are stored sealed with the vault's own key and start with `v2:`. On Android they live in the `vaultpass_paired_devices` DataStore; on the desktop in the `paired_device` table of the vault database. Sealing and opening need an unlocked vault. Stored keys without the `v2:` prefix come from protocol v1, which sent them in plain text; both apps delete those pairings, so the devices must pair again.

Device IDs are random UUIDs, created once per install. Android keeps its ID in the `vaultpass_sync_prefs` preferences; the desktop keeps its ID in the `sync-device-id` file in its data folder.

## 4. Discovery

While the Sync screen is open, each app broadcasts a beacon every 3 seconds to UDP port 53852, as long as it has at least one paired device:

```json
{"type":"BEACON","v":2,"n":"<16-byte nonce, hex>","ts":<sender time, ms>,"port":53853,"tags":["<8-byte tag, hex>", ...]}
```

A beacon names no device. It carries one tag per pairing, at most 64:

```
tag = first 8 bytes of HMAC-SHA256(pairKey, "VaultPass-v2 beacon" || nonce || u64(ts) || u32(port))
```

A receiver tries each of its own pair keys against the tags. When one matches, it records that device's IP address and port. It accepts a beacon from a device only if its `ts` is newer than the last one it accepted from that device, so a recorded beacon can't be replayed to point the device at another address. This compares timestamps from the same sender, so the two clocks don't need to agree. Each app ignores its own beacons by remembering the nonces it sent, and forgets a device that hasn't been heard from for 10 seconds.

Either app can broadcast the plain text `PING` to ask for beacons straight away. The answer is a normal beacon sent directly to the device that asked.

## 5. Handshake

Every TCP connection starts with a two-message handshake: an ephemeral ECDH exchange on P-256, authenticated by a 32-byte secret both sides already hold. That secret is the QR secret in `pair` mode and the pair key in `sync` mode, and it never travels on the wire.

**Framing.** Every message on the socket is:

```
'V' (0x56) | version 2 | type | int32 big-endian length | payload
```

Types are 1 = HELLO, 2 = REPLY, 3 = RECORD. A payload is at most 10 MB. A first byte of 0 or 1 means a protocol v1 peer, and the app shows an "update needed" message.

**HELLO** (initiator to responder), JSON:

```json
{"v":2,"mode":"pair|sync","e":"<ephemeral public key>","n":"<32-byte nonce>","hint":"<16 bytes>"}
```

- `e` is the X.509 SubjectPublicKeyInfo encoding of the key, in hex.
- `hint` is the first 16 bytes of `HMAC-SHA256(secret, "VaultPass-v2 hint" || 0x00 || mode || 0x00 || n)`. It lets the responder find which of its secrets the initiator is using without either side revealing one.

The responder computes the hint for each candidate secret: the current QR secret in `pair` mode, every stored pair key in `sync` mode. If none matches, it closes the connection without replying.

**REPLY** (responder to initiator), JSON:

```json
{"e":"<ephemeral public key>","n":"<32-byte nonce>","mac":"<32 bytes>"}
```

**Key derivation**, the same on both sides:

```
transcript = SHA-256("VaultPass-v2 transcript" || u32(len) || HELLO bytes || u32(len) || responder public key || u32(len) || responder nonce)
prk        = HKDF-Extract(salt = secret, ikm = ECDH shared secret)
okm        = HKDF-Expand(prk, "VaultPass-v2 keys" || transcript, 96 bytes, or 128 in pair mode)
```

`okm` splits into 32-byte keys: initiator-to-responder record key, responder-to-initiator record key, responder MAC key, and in `pair` mode the new pair key.

`mac = HMAC-SHA256(responder MAC key, "VaultPass-v2 responder" || transcript)`. The initiator checks it in constant time, which proves the responder holds the secret. The initiator proves the same with its first record: only the right keys decrypt it.

Received public keys must be valid P-256 points; anything else ends the connection. HELLO and REPLY are limited to 4,096 bytes.

## 6. Record layer

After the handshake every message is a RECORD, encrypted with AES-256-GCM:

- **Keys.** Each direction has its own key from section 5.
- **Nonce.** Four zero bytes followed by that direction's record counter as a big-endian u64, starting at 0.
- **Associated data.** The three bytes `'V', 2, 3`.

A replayed, reordered, dropped or modified record fails authentication and ends the connection. Both apps wipe the record keys when the connection closes.

## 7. Messages

Each record holds one message, encoded as JSON. The `type` field names the message:

| Message | Fields | Sent by |
|---|---|---|
| `PairingInfo` | `deviceId`, `deviceName` | Phone, after a `pair` handshake |
| `PairingAcceptance` | `deviceId`, `isAccepted` | Desktop, after its user decides |
| `SyncRequest` | `deviceId`, `deviceName` | Initiator of a sync |
| `SyncAcceptance` | `deviceId`, `isAccepted` | Responder, after its user decides |
| `PayloadBatch` | `encryptedBatchJson`, `part`, `last` | Both, once the sync is accepted |
| `MergePlanBatch` | `planJson`, `part`, `last`, `expectedFingerprint` | Reviewer (the initiator) |
| `MergePlanDecision` | `accepted`, `code` | Approver (the responder) |
| `SyncFinished` | `code`, `fingerprint`, `applied` | Both, after applying the plan |
| `KeepAlive` | `sentAt` | Both, every 30 seconds during a sync |
| `CancelSync` | `reason`, `code` | Either side, at any time |

Despite its name, `PayloadBatch.encryptedBatchJson` is a plain JSON array of records. The record layer encrypts it, like every other message.

Each app accepts a message only when it makes sense at that point, and a message outside these rules ends the connection as a protocol error:

- `PairingInfo` and `PairingAcceptance` only on a `pair` connection, once each, in the right direction.
- `SyncRequest` only once, and only naming the device whose pair key authenticated the handshake.
- `PayloadBatch` only after the sync was accepted.
- `MergePlanBatch` only to the responder, after it accepted the sync, until the last part.
- `MergePlanDecision` only to the reviewer, after it sent the whole plan, once.
- `SyncFinished` once per session. `KeepAlive` only on a `sync` connection.
- `CancelSync` always.

## 8. A sync session

The device that starts the sync is the **reviewer**; the other device is the **approver**. Only the reviewer compares the vaults and decides what happens to each entry. The approver sees a summary and applies the same plan, so both vaults end up identical.

1. The reviewer connects to the address from discovery and runs a `sync` handshake with the pair key.
2. It sends `SyncRequest`. The approver's user accepts or declines; a decline ends the session with the code `declined`.
3. Both send all their entries and deleted-entry records as numbered `PayloadBatch` parts. `last = true` ends each side's vault.
4. The reviewer compares the two vaults (section 9) and shows the review. Its user picks an outcome for each entry and must answer every conflict.
5. The reviewer sends the plan as numbered `MergePlanBatch` parts. The last part carries `expectedFingerprint`: the reviewer's vault fingerprint after applying the plan.
6. The approver validates the plan and shows "N added, N updated, N deleted" with Apply and Decline. An empty plan is accepted without asking. It answers `MergePlanDecision`; a decline carries the code `plan_rejected` and nothing changes on either device.
7. Both apply the plan in one database transaction and send `SyncFinished` with their new fingerprint and the number of entries they changed. If applying fails, the transaction rolls back and `SyncFinished` carries `merge_failed`.
8. Each compares the other device's fingerprint with its own:
   - **Equal.** Success. Both record the sync time for the other device and disconnect.
   - **Different.** Both show "The devices still hold different entries" and offer to sync again. Nothing is undone, and the sync time is not recorded, so the next sync compares everything again.

"Devices are up to date (Finish)" runs the same steps with an empty plan, so it also records the sync time and disconnects.

**Time limits.** Each step has one limit, shared by both apps (`SyncTimeouts` in the core):

| Waiting for | Limit |
|---|---|
| The handshake, and the first message on a new connection | 10 seconds |
| The other user to accept the sync (`AWAITING_APPROVAL`) | 60 seconds |
| The next vault part (`EXCHANGING`) | 60 seconds, restarted by each part |
| The answer to the plan (`AWAITING_PLAN_APPROVAL`) | 60 seconds, restarted by each plan part |
| Applying and confirming (`APPLYING`, `CONFIRMING`) | 60 seconds |
| The reviewer's user choosing (`REVIEWING`) | No limit |
| Any message at all on an open connection | 120 seconds |

During the review nothing else crosses the connection, so both sides send `KeepAlive` every 30 seconds to stay under the 120-second idle limit. A `KeepAlive` does not restart a step's own limit, so a device that stops working still times out. Either user can cancel while the reviewer is choosing.

**Errors.** When a session ends early, the device that stopped sends `CancelSync` with one of these codes if the connection still works, and both show the message in an error dialog:

| Code | Message |
|---|---|
| `cancelled` | The sync was cancelled. |
| `declined` | The other device declined the sync. |
| `plan_rejected` | The other device declined the changes. |
| `timed_out` | The other device stopped responding. |
| `connection_lost` | The connection to the other device was lost. |
| `protocol` | The other device sent something unexpected, so the sync was stopped. |
| `peer_outdated` | The other device runs an older version of VaultPass. Update it on both devices. |
| `vault_locked` | The vault was locked, so the sync was stopped. |
| `vault_unreadable` | This device could not read its own vault, so the sync was stopped. |
| `merge_failed` | The changes could not be saved, so nothing was changed. |
| `not_identical` | The devices still hold different entries. Sync them again. |
| `too_much_data` | The other device sent more data than a sync allows. |
| `unknown` | The sync stopped for an unknown reason. |

## 9. Data model

**Records.** Each entry travels as a `SyncRecord`:

```
syncId, title, username, password, url, notes, category, tags[], customFields[{key, value}],
favorite, updatedAt, deleted, deletedAt
```

`syncId` is the same on every device: 1 to 64 characters from `A-Z a-z 0-9 _ -`. A deleted entry travels as a tombstone, with `deleted = true`, `deletedAt` set and no content. The reviewer drops received records with an invalid or repeated `syncId` before comparing. Records are sent as JSON arrays of about 1 MB each.

**Content hash.** SHA-256 over every synced field in exact case, with tags and custom fields sorted, plus the favourite flag. Each string is length-prefixed. Timestamps are not included. Two records with the same hash hold the same content.

**Comparing vaults.** The reviewer puts each entry in one category. "Changed since the last sync" means `updatedAt` is later than the last successful sync with this device.

| Category | Meaning | Default outcome |
|---|---|---|
| New on the other device | Only the approver has it | Use the other device's version |
| New on this device | Only the reviewer has it | Keep this device's version |
| Unchanged | Same content on both | Leave unchanged |
| Changed on the other device | Only the approver changed it since the last sync | Use the other device's version |
| Changed on this device | Only the reviewer changed it since the last sync | Keep this device's version |
| Conflict | Both changed it since the last sync | None: the user must choose |
| Deleted on the other device | The approver deleted it | Delete on both, or keep this device's version if the reviewer edited it after the deletion |
| Deleted on this device | The reviewer deleted it | Delete on both |

On the first sync with a device, the newer `updatedAt` decides between "changed on the other device" and "changed on this device".

**Entries from before syncIds.** Entries created before protocol v2 have a different `syncId` on each device. The reviewer matches them by title and username, trimmed and ignoring case, but only when that pair is unique on both devices. A matched entry keeps the approver's `syncId`, and the plan tells the reviewer to rename its own entry (`renameFrom`). A plan can carry only one old id, so the approver must never need a rename.

**Merge plan.** The outcomes become a list of plan items:

| Outcome | Plan item |
|---|---|
| Use the other device's version / Keep this device's version | `UPSERT` with the chosen record (an edited record gets `updatedAt` = now) |
| Delete on both | `RECYCLE` with `deletedAt` |
| Leave unchanged | Nothing, or `RENAME_ONLY` for a matched pre-syncId entry |

Before applying a plan, a device checks it and refuses it if an id is invalid or repeated, an `UPSERT` has no record or its record has a different id or is a tombstone, or the plan has more than 200,000 items. Applying goes item by item in one transaction:

1. Apply `renameFrom` if this device has an entry with that id.
2. `UPSERT` updates the entry, restoring it from the recycle bin if needed, or inserts it. It also removes any tombstone for that id.
3. `RECYCLE` moves the entry to the recycle bin. If this device doesn't have the entry, the item changes nothing.

**Fingerprint.** The first 32 hex characters of SHA-256 over the sorted lines `syncId:contentHash` of every active entry. Two devices that hold the same entries produce the same fingerprint.

**Entries a device can't decrypt** are left out of what it sends and out of its fingerprint, and a reviewer also leaves them out of the plan. If the other device holds a readable copy, the fingerprints can differ, and the sync ends with "The devices still hold different entries".

## 10. Limits

| Limit | Value |
|---|---|
| One message on the wire | 10 MB |
| One `PayloadBatch` or `MergePlanBatch` part | About 1 MB of JSON |
| Parts per vault or per plan | 5,000 |
| Records received per sync | 200,000 |
| Items per merge plan | 200,000 |
| Beacon tags | 64 |

Entry fields have the same limits in both apps' editors (`EntryLimits` in the core): title 200 characters, username 300, password 1,000, website 500, notes 20,000, custom field name 100 and value 2,000, 50 custom fields, 25 tags of up to 50 characters. The limits match so that an entry made on one device can be saved after editing on the other.

## 11. Changing the protocol

- Change the shared core in one app, copy it to the other, and confirm the checksums of all files match.
- Keep the message classes in both apps' `SyncNetworkFrames.kt` identical: the same `@SerialName`, fields, types and defaults.
- Protocol v1 and v2 devices can't sync. The framing rejects v1 frames with an "update needed" message, and v1 pairings are deleted. A future incompatible change should follow the same pattern: a new version byte and a clear message, never silent failure.
