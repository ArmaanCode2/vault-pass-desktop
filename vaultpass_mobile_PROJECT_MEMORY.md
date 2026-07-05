# VaultPass Project Memory

## Overview
VaultPass is an offline-first Android password manager built using Kotlin, Jetpack Compose, Room, DataStore, Android Keystore, and MVVM architecture. The application stores encrypted credentials locally on the device and heavily utilizes system-level security integrations.

*(For a deep dive into layers, data flows, and structural constraints, see `ARCHITECTURE.md`)*

---

## Current Features
* **Authentication:** Master Password, Biometric (AES-hardware bound).
* **Security:** AES-GCM vault encryption, Screenshot protection (FLAG_SECURE), Clipboard auto-clear, Process-level Auto-Lock.
* **Vault:** CRUD operations, Custom Fields (masked by default), Search.
* **Autofill System:** Android framework integration with intelligent DOM traversal, heuristics, and native app label scoring.
* **Hygiene:** Security Center (Weak, Reused, Missing tracking).
* **Utilities:** Multi-Format Import/Export (TXT, JSON, VPEX), Password Generator, Recycle Bin (7-day soft delete).
* **UI:** Dynamic Theme & Accent Color System, Privacy Policy Integration.

---

## Security Architecture
Security logic relies heavily on a multi-tier encryption approach:
1. **Master Password**: Authenticates via PBKDF2-HMAC-SHA256 hash verified using constant-time `MessageDigest.isEqual()`.
2. **Dynamic KDF Configuration**: Iteration counts are tracked dynamically via DataStore (`MASTER_KDF_VERSION`, `MASTER_KDF_ITERATIONS`, `MASTER_KDF_ALGORITHM`).
3. **Software DEK**: Used for in-memory AES-GCM encryption/decryption of Vault entries. Memory-scrubbed instantly on vault lock.
4. **DEK Wrapping**: The DEK is encrypted using a KEK (Key Encryption Key) derived from the Master Password.
5. **Android KeyStore (Hardware-Backed)**: Used to securely wrap the Software DEK for Biometric Unlock.

## Security Status
**Implemented:**
* Versioned KDF architecture
* Dynamic KDF configuration
* KDF migration framework
* Constant-time password verification
* Brute-force protection
* Biometric reset handling

## Current KDF Configuration
* PBKDF2-HMAC-SHA256
* 300,000 iterations
* Dynamic iteration storage (`MASTER_KDF_ITERATIONS`)
* KDF version storage (`MASTER_KDF_VERSION`)
* KDF algorithm storage (`MASTER_KDF_ALGORITHM`)

## Current Database State
* **Room Version:** 2
* **Existing Migrations:** `MIGRATION_1_2` (Adds `isDeleted` and `deletedAt` for Recycle Bin soft-delete support).

## Brute Force Protection
* **Failed-attempt storage mechanism**: Jetpack DataStore integer and timestamp tracking (`FAILED_AUTH_ATTEMPTS`, `LAST_FAILED_AUTH_TIMESTAMP`).
* **Cooldown thresholds**: Incremental thresholds at 5, 10, 15, and 20+ failed attempts.
* **Cooldown durations**: 30 seconds, 60 seconds, 5 minutes, 15 minutes respectively.
* **Reset behavior**: Successful password authentication instantly purges tracking metrics and resets to zero.
* **Biometric reset behavior**: Successful biometric authentication instantly purges tracking metrics and resets to zero.

---

## Authentication Flow
* App resumes to `LockScreen` if `isUnlocked` is false.
* Biometric unlock is automatically prompted if enabled, using Android KeyStore authenticated Ciphers.
* Master Password bypasses Biometric verification to regenerate the Software DEK directly.
* Successful authentication decrypts the vault and transitions the user to the inner NavHost.

---

## Auto-Lock System
* Utilizes a hybrid lifecycle approach for maximum reliability.
* Configurable timeout (Immediately, 30s, 1m, 5m, 15m, Never).
* For delayed timers: Relies on `ProcessLifecycleOwner` to securely track app-wide background state.
* For "Immediately": Hooks directly into `MainActivity.onStop()` and pipes to `VaultViewModel.handleActivityStopped()`.
* Safe exemptions (`isPerformingSystemOperation`) exist for trusted system UI overlays (like the Import/Export file picker) to prevent accidental lockouts.

---

## Autofill System
* Features a robust `AutofillService` implementation bridging VaultPass directly to the Android OS.
* **DOM Traversal**: Uses Breadth-First Search across `AssistStructure` nodes to discover fields.
* **Heuristics**: Safely evaluates `autofillHints`, field IDs, and `inputType` variations (including text, web, and numeric passwords).
* **Matching**: Implements a highly accurate cumulative scoring algorithm prioritizing Website domains first, then App Labels, then fuzzy Package Names.
* **Diagnostics**: A comprehensive runtime telemetry repository tracks gatekeeper rejections and traversal logic.

---

## Import / Export System
* Features a robust Multi-Format Export/Import system (`.txt`, `.json`, `.vpex`) accessible via Android's `ActivityResultContracts` file pickers.
* Encrypted `.vpex` exports utilize Base64 AES-GCM wrapping.
* Implements a smart cascading fallback importer that natively reads files of any type (`*/*`) to intelligently parse structural heuristics, guaranteeing legacy backup restoration.
* Integrates the `isPerformingSystemOperation` exemption flag to temporarily bypass the Auto-Lock system while the Android OS file picker is active.

---

## Security Analyzer
* Analyzes all `VaultEntry` models and outputs `SecurityStatsSummary`.
* Groups entries dynamically into Weak, Reused, and Missing metrics.
* Fully replaces the old Dashboard filter logic, acting as the Single Source of Truth for password hygiene.

---

## Recycle Bin
* **Architecture**: Uses a soft-delete approach (`isDeleted = true`) to prevent accidental data loss.
* **Features**: Supports restoring deleted entries or permanently deleting them.
* **Auto-Cleanup**: Deleted entries are automatically hard-deleted after 7 days.
* **Isolation**: Deleted entries are strictly excluded from normal vault lists, Autofill datasets, Security Center calculations, and all Export formats.

---

## Theme & Accent Colors
* Deep integration with Material 3 dynamic color tokens (`MaterialTheme.colorScheme`).
* All surface and border colors dynamically adapt using tokens like `surfaceVariant` and `onSurface.copy(alpha)` to guarantee optimal contrast.
* Prevents text contrast bugs by explicitly avoiding hardcoded UI hex colors.

---

## Navigation Structure
Powered by Compose Navigation (`NavHost`):
* `dashboard` (Main vault list)
* `add_entry` / `edit_entry/{id}`
* `entry_details/{id}`
* `security` (Security Center: `weak_passwords`, `reused_passwords`, `missing_passwords`)
* `settings`
* `generator`

---

## Known Design Decisions
* **Autofill Gatekeeper Strictness**: The DOM parser explicitly rejects UI layout containers (`TextInputLayout`) and enforces a strict `isEditable` requirement to prevent Android from silently dropping invalid `FillResponse` datasets. 
* **Autofill Hint Bypass**: We safely bypass the strict gatekeeper *only* for nodes declaring explicit password hints (`current-password`, `new-password`), trusting the OS/browser engine directly to fix WebViews while maintaining security against layout theft.
* **Direct Navigation for Security Lists**: Tapping a dashboard stat card directly navigates into the Security Center sub-routes, deprecating legacy main-list filter behavior.
* **Biometric DEK Wrapping**: Requires presenting a `BiometricPrompt` during the Settings toggle to satisfy Android KeyStore `Cipher` initialization requirements.

---

## Project Status
**Marked as complete:**
* KDF architecture upgrade
* Migration framework
* Brute-force protection
* Authentication hardening
* Recycle Bin (Soft-Delete)
* Privacy Policy Integration

**Release Readiness:** The core application features, data structures, and foundational privacy compliance are fully implemented and verified in the codebase.

## Roadmap
* Cross-device synchronization via encrypted cloud providers.
* Native Windows/Desktop companion app.
* Expanded Autofill dataset capabilities (e.g., Credit Cards, Addresses).
* Automated scheduled background backups.
