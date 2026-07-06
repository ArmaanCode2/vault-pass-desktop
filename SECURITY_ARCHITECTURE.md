# VaultPass Desktop - Security Architecture Manifesto

This document formalizes the architectural defense mechanisms implemented in VaultPass Desktop. It serves as a permanent reference for future development to ensure the offline-first, local-only, and zero-knowledge paradigms are never compromised.

## 1. Domain Dominance

The core philosophy of VaultPass is that **the Domain Layer dictates security, not the UI.**
*   **Session State Supremacy**: The UI does not manage whether the app is locked or unlocked. The domain `SessionManager` tracks states (`LOCKED`, `UNLOCKING`, `BACKGROUND`). If the OS minimizes the app, the domain unilaterally transitions to `BACKGROUND` and wipes keys, forcing the UI to adapt without any explicit UI logic.
*   **Reactive Flow Data**: Data flows upward from the local database via Kotlin `Flow`s. If a background sync job modifies the database, the Repositories automatically emit the new state to the ViewModels. The UI is completely agnostic to how data arrived on the disk.

## 2. Secure Memory Management

Standard Kotlin/Java `String` objects are immutable and pooled, meaning sensitive data can linger in the heap indefinitely and be extracted via memory dumps.
*   **Type-Safe Wrapping**: VaultPass uses `SecureCharArray` and raw `ByteArray` structures for Master Passwords, DEKs (Data Encryption Keys), and unencrypted secrets.
*   **Explicit Wiping**: Because these structures wrap primitive arrays, they expose explicit `.wipe()` methods. When the `SessionManager` transitions to `CLOSING` or `LOCKED`, it loops through active structures and overwrites them with zeros (`\u0000`).
*   **Time-Limited Clipboard**: Passwords copied to the clipboard use the `SecureClipboardManager`, which automatically scrubs the OS clipboard buffer after a predetermined TTL (e.g., 30 seconds).

## 3. Cryptographic and Format Agility

VaultPass never hardcodes cryptographic parameters, ensuring future-proofing against algorithmic obsolescence.
*   **Vault Format Versioning**: Every vault tracks its structural version. Future versions of VaultPass will use the `MigrationRunner` to safely upgrade old SQLite schemas.
*   **Dynamic KDF Metadata**: PBKDF2 iterations, algorithms, and salt lengths are stored alongside the vault. VaultPass can seamlessly upgrade a user from 100,000 iterations to Argon2id without breaking backward compatibility.
*   **Cipher Versioning**: The application supports migrating from AES-256-GCM to future ciphers (like XChaCha20-Poly1305) by reading the vault's embedded `EncryptionConfig`.

## 4. Centralized Error Handling

VaultPass prevents cryptographic or database crashes from ever leaking untyped, raw exceptions to the presentation layer.
*   **VaultError Hierarchy**: All exceptions (e.g., `SQLiteException`, `IOException`) are caught in the Repository layer and mapped to typed domain errors like `DatabaseError.DiskFull` or `AuthError.IncorrectPassword`.
*   **Result Pattern**: The `RepositoryResult` enforces this mapping at compile-time, guaranteeing that the Compose UI can exhaustively match on known error states to display localized warnings instead of cryptic stack traces.

## 5. Secure Logging

*   **Type-Safe Redaction**: `SecureCharArray` and domain models (like `VaultEntry`) explicitly override `toString()` to return redacted placeholders (`[REDACTED]`).
*   **Sanitization Middleware**: All logging is routed through `VaultLogger`, which utilizes a `LogSanitizer` to heuristically strip keys, passwords, and JWTs before they are written to disk. Debugging remains safe.

## 6. OS & Platform Abstraction

To ensure VaultPass can trivially port to macOS or Linux, all native interactions are strictly decoupled.
*   **Platform Boundaries**: Interfaces like `SystemTrayProvider`, `NotificationProvider`, `BiometricAuthenticator`, and `FileDialogProvider` define the required capabilities. The domain never imports Windows APIs. Support for a new OS is achieved merely by swapping out the data layer implementation.

## 7. Feature Isolation (Toggles)

*   **FeatureManager**: All new, untested, or experimental features (LAN Sync, Windows Hello, Browser Extensions) are hidden behind `FeatureFlag` conditions. The UI reactively observes these flags via Flow, allowing for safe merging of code into `main` and instant UI updates when a feature is remotely or locally enabled.
