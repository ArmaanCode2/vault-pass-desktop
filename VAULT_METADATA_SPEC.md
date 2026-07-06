# VaultPass Metadata Specification

This document permanently defines the architecture and fields of the VaultPass `metadata.json` file. It serves as the single source of truth for vault initialization, compatibility checking, and migration management.

## 1. Storage Location
Metadata is stored in a JSON file at `~/.vaultpass/metadata.json`. This abstracts the existence of the vault away from the actual database file, enabling complex setups like syncing the DB independently or storing the DB in a different directory while keeping metadata local.

## 2. Core Structure (`VaultMetadata`)

The metadata model is strictly defined and versioned to ensure forwards and backwards compatibility.

| Field | Type | Description |
|---|---|---|
| `initialized` | `Boolean` | True if the vault has been successfully set up. Used by the SessionManager to determine `FirstLaunch` vs `Locked` states. |
| `vaultVersion` | `Int` | The structural version of the Vault database schema. Crucial for triggering SQLite migrations in the future. |
| `metadataVersion` | `Int` | The version of this JSON metadata file format itself. |
| `kdfVersion` | `Int` | The version of the Key Derivation Function parameters (e.g., PBKDF2 vs Argon2id). |
| `encryptionVersion` | `Int` | The version of the underlying symmetric cipher (e.g., AES-GCM vs XChaCha20). |
| `settingsVersion` | `Int` | The version of the user's application settings schema. |
| `migrationVersion` | `Int` | Tracks the overall migration engine state to prevent cyclic migration loops. |
| `createdAt` | `Long` | Unix timestamp of when the vault was created. |
| `lastOpenedAt` | `Long` | Unix timestamp of when the vault was last successfully unlocked. |
| `createdWithAppVersion` | `String` | The semantic version of VaultPass Desktop that created this vault. |
| `lastOpenedAppVersion` | `String` | The semantic version of VaultPass Desktop that most recently unlocked the vault. |

## 3. Migration Architecture

To avoid monolithic migration functions, VaultPass uses a highly scalable `MigrationRunner` architecture:

1. **`MigrationType`**: Defines the target subsystem (Vault, Metadata, KDF, Encryption, Settings).
2. **`Migration`**: An interface defining a `targetVersion` and an execution function.
3. **`MigrationRegistry`**: A central registry where future migrations can register themselves without modifying core logic.
4. **`MigrationRunner`**: On startup, compares current metadata versions against the registry and sequentially executes required upgrades, ensuring an unbroken chain of backward compatibility.

## 4. Cryptographic Compatibility

This architecture is completely decoupled from actual cryptography:
*   **PBKDF2 Integration**: When implemented, PBKDF2 will read `kdfVersion` (and potentially future iterations/salt fields stored in a `KdfConfig` object inside metadata) to verify the master password.
*   **AES-GCM Integration**: The `encryptionVersion` will dictate which cipher the Data Encryption Keys are wrapped in.
*   **Android / KMP Compatibility**: By using standard `kotlinx-serialization-json`, the exact same metadata file and parsers can be used natively on Android, iOS, Windows, macOS, and Linux.
