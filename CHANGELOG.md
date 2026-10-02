# VaultPass Desktop - Changelog

## [Unreleased]
LAN sync with VaultPass for Android, rebuilt for security and reliability. Update both apps together: this version can't sync with older versions, and every phone must be paired again once.

### Added
- **Reviewed sync that ends identical on both devices:** The device that starts a sync reviews every difference and chooses what happens to each entry; conflicts must be answered. The other device sees a summary and applies or declines it. Both apply the same changes and compare a fingerprint of their vaults, so a sync only reports success when the two vaults match.
- **Deletions sync:** Entries deleted on one device, including ones purged from the Recycle Bin, are deleted on the other device too.
- **Custom fields:** Entries can hold custom fields, edited in the entry dialog, and they sync with the phone.
- **Sync errors you can read:** Every failed sync opens the error dialog with its reason, and the other device is told why the sync stopped.
- **Waiting and approval dialogs:** While the phone reviews a sync, this PC shows a waiting dialog with Cancel. When the phone sends its changes, this PC shows what will change before applying it.

### Changed
- **Networking only while syncing:** Discovery and the sync listener run only while Settings > Sync is open or a pairing QR code is shown, and stop when the vault locks.
- **Firewall rules:** The rules for TCP 53853 and UDP 53852 now apply to Private networks only. Older rules that covered every network profile are removed.
- **Database:** Schema changes are tracked with SQLite `user_version` migrations. The `sync_tombstone` table records deleted entries for sync. Entries that can't be decrypted are marked and left out of sync.
- **Larger vaults:** Entries are sent in parts of about 1 MB.
- **Editor limits shared with the phone:** Title 200 characters, username 300, password 1,000, website 500, notes 20,000, custom field name 100 and value 2,000, up to 50 custom fields and 25 tags. Save is blocked with a message while a field is over its limit.
- **Settings and the Privacy Policy** now name the key derivation the vault really uses: PBKDF2-HMAC-SHA256. They previously said Argon2id.

### Security
- **Sync protocol v2:** Each connection starts with a P-256 key exchange authenticated by the pairing key, and every message is encrypted with AES-256-GCM using a key and a counter per direction. A replayed, reordered or altered message ends the connection.
- **The QR code holds a one-time secret** that never goes over the network, and a new phone must be approved on this PC. Pairing keys are stored encrypted with the vault key. Pairings from the old protocol, whose keys were sent and stored in plain text, are deleted.
- **Discovery beacons carry no device name or ID**, and a recorded beacon can't redirect a device to another address.
- **Random device ID:** This PC's sync identity is a random ID stored in the `sync-device-id` file in its data folder.
- **One sync connection at a time:** Other devices are turned away while a sync is in progress.

### Fixed
- Finish in the sync review no longer races its own cancel, so the phone is always told the sync finished.
- The phone finishing its sync no longer closes a review that is still open on this PC.
- The sync time limit now restarts with each part received, so large vaults no longer time out mid-transfer.
