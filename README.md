<div align="center">
  <img src="assets/logo.png" alt="VaultPass Logo" width="128" height="128" />
  <h1>VaultPass Desktop</h1>
  <p><strong>Offline-First, Zero-Knowledge Password Manager for Desktop Workstations</strong></p>
  <p>Companion desktop application to VaultPass Android, engineered with Kotlin and Jetpack Compose for Desktop.</p>
</div>

---

> Note: VaultPass Desktop is currently under active development. All operations run locally on your hardware. Maintain independent encrypted backups of your credentials.

---

## Overview

VaultPass Desktop is a standalone, offline-first credential vault and desktop companion to VaultPass for Android. Built using Kotlin Multiplatform and Jetpack Compose for Desktop, it provides a native desktop interface for creating, managing, analyzing, and securely synchronizing passwords without relying on proprietary cloud services, third-party sync relays, or online user accounts.

All cryptographic operations, key derivations, and data storage occur exclusively on the local machine. Sensitive data is protected by authenticated symmetric encryption (AES-256-GCM) with dynamic key derivation parameters, zeroized memory structures, and hardware-aware session boundaries.

---

## Who Is VaultPass Desktop For?

VaultPass Desktop is engineered for users who require strict sovereignty over their digital credentials:

* **Privacy-Conscious Individuals**: Users who want complete control over their credentials and refuse to upload vaults to third-party cloud providers.
* **Mobile-to-Desktop Users**: People who use VaultPass on Android and desire a seamless, private way to synchronize credentials directly across their home or office local Wi-Fi without internet exposure.
* **Security & IT Professionals**: Engineers, developers, and system administrators managing sensitive credentials across isolated machines who need transparent, auditable cryptography.
* **Air-Gapped & Restricted Environments**: Workstations operating within offline facilities, virtual isolated networks, or enterprise environments where external cloud synchronization is prohibited or firewalled.

---

## Core Features

### Cryptography & Vault Security
* **AES-256-GCM Vault Encryption**: Every record payload is encrypted using authenticated AES-GCM with distinct initialization vectors to guarantee both confidentiality and integrity.
* **PBKDF2-HMAC-SHA256 Key Derivation**: Master passwords are transformed into Key Encryption Keys (KEK) using industry-standard iterations and cryptographically secure salts.
* **Zeroized Memory Management**: Master passwords and Data Encryption Keys (DEK) are handled via custom secure byte and character wrappers, explicitly zeroed out (`\u0000`) in memory when the vault locks or the session terminates.
* **Constant-Time Verification**: Master password authenticity is verified using constant-time digest comparisons (`MessageDigest.isEqual`) to eliminate side-channel timing attacks.
* **Brute-Force Lockout Engine**: Dynamic incremental delay penalties protect against local brute-force attempts.

### Peer-to-Peer Encrypted LAN Sync
* **Zero-Cloud Direct Transfer**: Synchronize credentials directly between VaultPass Desktop and VaultPass Android over local Wi-Fi or hotspot networks via encrypted TCP sockets (port 53853) and UDP discovery beacons (port 53852).
* **Cryptographic QR Handshake**: Establish authenticated pairings by scanning desktop QR codes containing short-lived tokens and multi-IP discovery hints.
* **Mutual Interactive Approval**: Both devices must explicitly approve incoming pairing and synchronization sessions before any data is transferred.
* **3-Way Difference Review Engine**: Prior to committing changes, users inspect incoming modifications, additions, and local deletions with full field-by-field inspection and override controls.
* **De-Resurrection Safeguards**: Records moved to the local Recycle Bin are tracked to prevent deleted entries from being unintentionally resurrected during sync.

### Vault Organization & Management
* **Rich Entry Metadata**: Store titles, usernames, passwords, website URLs, custom tags, categories, and formatted notes.
* **Unlimited Custom Fields**: Extend any entry with custom key-value pairs for API tokens, security questions, PINs, and server ports.
* **Password History**: Automatically archive previous passwords with timestamps and change justifications when updating credentials.
* **Favorites & Fast Categorization**: Group and filter credentials by categories (Personal, Work, Finance, Social, Development) or mark them as favorites.
* **Deep Real-Time Search**: Instant filtering across entry titles, usernames, websites, categories, tags, and custom fields.

### Security Center & Audit Engine
* **Hygiene Health Tracking**: Real-time evaluation of total vault health and password strength distribution.
* **Vulnerability Flags**: Automatic flagging of weak passwords, reused credentials across different services, and missing passwords.
* **Privacy Masking**: Sensitive fields on the dashboard and audit screens are obscured by default to protect against shoulder surfing.

### Session Management & Clipboard Safety
* **Inactivity Auto-Lock**: Configurable background timers automatically unwrap keys and lock the vault after user-defined periods of inactivity.
* **Native Session Detection**: Monitors operating system session events (screen lock, user switch) to immediately zeroize in-memory keys and protect data when stepping away from the desk.
* **Secure Clipboard Scrubbing**: Passwords copied to the system clipboard are purged automatically after a configurable timeout (default 30 seconds).

### Backup, Export & Multi-Format Import
* **Encrypted VPEX Backups**: Cross-platform interoperability with VaultPass Android using encrypted `.vpex` (Base64 AES-GCM) container files.
* **Universal Import Engine**: Import existing vaults from popular formats:
  * Bitwarden JSON exports
  * KeePass CSV / JSON exports
  * Plaintext CSV and JSON structures
* **Data Portability**: Export vault contents to decrypted CSV or JSON for migration, backups, or independent auditing.

---

## Security Architecture

The application design follows a strict domain-dominance model:

```
+------------------------------------------------------------------+
|                   Jetpack Compose Desktop UI                     |
|           (Reactive ViewModels, Stateless Composables)           |
+---------------------------------+--------------------------------+
                                  | Observes Flows
+---------------------------------v--------------------------------+
|                          Domain Layer                            |
|  * SessionManager (LOCKED, UNLOCKING, ACTIVE, BACKGROUND)        |
|  * CryptoManager (AES-256-GCM, PBKDF2-HMAC-SHA256)              |
|  * SyncDiffEngine (3-Way Merge, Conflict Resolution)             |
|  * SecureMemory (SecureCharArray, Zeroization Hooks)             |
+---------------------------------+--------------------------------+
                                  | Invokes Repositories
+---------------------------------v--------------------------------+
|                           Data Layer                             |
|  * SQLiteVaultDataSource (Encrypted SQLite DB via JDBC)          |
|  * LanSocketTransport (Encrypted TCP Frame Exchange)             |
|  * LanDiscoveryManager (UDP Beacon Broadcast & Liveness)         |
|  * WindowsPlatform (DPAPI, Session Lock, Firewall Integration)   |
+------------------------------------------------------------------+
```

* **Domain Supremacy**: The UI never dictates security states. The core `SessionManager` unilaterally transitions states based on timeout and OS triggers, purging volatile keys from memory and notifying the UI reactively.
* **Cryptographic Agility**: Storage formats, KDF versions, iterations, and symmetric cipher suites are encoded in metadata (`~/.vaultpass/metadata.json`), enabling background cryptographic migrations without breaking vault backward compatibility.
* **Layered Error Handling**: Raw database and network exceptions are never passed to the presentation layer. Typed domain results (`RepositoryResult.Success`, `RepositoryResult.Error`) preserve system integrity.

---

## Technology Stack

| Layer | Technologies |
|---|---|
| **Language** | Kotlin 1.9.23 |
| **UI Framework** | Jetpack Compose for Desktop, Material 3, Skiko |
| **Concurrency** | Kotlin Coroutines, StateFlow, SharedFlow, SupervisorJobs |
| **Local Persistence** | SQLite JDBC Driver (`org.xerial:sqlite-jdbc`) |
| **Serialization** | Kotlinx Serialization JSON (`org.jetbrains.kotlinx:kotlinx-serialization-json`) |
| **Cryptography** | Java Cryptography Architecture (`javax.crypto`, `AES/GCM/NoPadding`, `PBKDF2WithHmacSHA256`) |
| **Native Integration** | Java Native Access (`net.java.dev.jna:jna`, `jna-platform`) |
| **QR Code Engine** | ZXing Core & JavaSE (`com.google.zxing`) |
| **Build System** | Gradle 8.x with Kotlin DSL |

---

## Getting Started: Clone and Run from Source

VaultPass Desktop runs on Windows, macOS, and Linux workstations with a compatible Java Development Kit installed.

### Prerequisites

Ensure you have the following installed on your development machine:

* **Java Development Kit (JDK)**: JDK 17 or higher (Eclipse Temurin, OpenJDK, or Amazon Corretto recommended).
* **Git**: Version 2.30 or newer.

To verify your environment, run:

```bash
java -version
git --version
```

### 1. Clone the Repository

Clone the project repository to your local drive using Git:

```bash
git clone https://github.com/ArmaanCode2/vault-pass-desktop.git
cd vault-pass-desktop
```

### 2. Run the Application

Use the included Gradle wrapper to download dependencies, compile the project, and launch the Compose Desktop application.

#### On Windows (PowerShell or Command Prompt):

```powershell
.\gradlew.bat run
```

#### On Linux or macOS:

```bash
chmod +x gradlew
./gradlew run
```

The application will launch with a setup screen on first execution, prompting you to create your master password and initialize your local vault.

---

## Running Unit & Integration Tests

The test suite validates cryptographic round-trips, cross-platform import formats, diff reconciliation, and peer-to-peer frame communication.

To execute all unit tests:

#### On Windows:

```powershell
.\gradlew.bat test
```

#### On Linux or macOS:

```bash
./gradlew test
```

Test reports are generated in `build/reports/tests/test/index.html`.

---

## Project Structure

```
vault-pass-desktop/
├── assets/
│   ├── logo.png                   # VaultPass brand mark
│   └── logo.svg                   # Scalable vector logo
├── src/
│   ├── main/
│   │   ├── kotlin/com/vaultpass/desktop/
│   │   │   ├── data/              # SQLite database, JNA platform bridges, repositories
│   │   │   │   ├── crypto/        # Cipher implementation, key storage, zeroization
│   │   │   │   ├── database/      # SQLite connection managers and queries
│   │   │   │   ├── exportimport/  # Bitwarden, KeePass, CSV, and VPEX parsers
│   │   │   │   ├── platform/      # Windows session listeners and firewall helper
│   │   │   │   └── sync/          # TCP socket transport and UDP discovery manager
│   │   │   ├── domain/            # Business models, diff engine, session state
│   │   │   └── ui/                # Compose screens, components, view models, and theme
│   │   └── resources/             # Application icons and fonts
│   └── test/                      # Unit tests, cryptographic tests, and sync mocks
├── build.gradle.kts               # Compose Desktop and Gradle dependencies
├── settings.gradle.kts            # Project definitions
├── SECURITY_ARCHITECTURE.md       # Formal security and domain specification
└── VAULT_METADATA_SPEC.md         # Schema and metadata specification
```

---

## Cross-Platform Ecosystem

VaultPass Desktop is part of the VaultPass offline security suite:

* **VaultPass Android**: The offline-first mobile password manager with biometric authentication and Android Autofill service integration.
* **VaultPass Desktop**: The workstation companion for high-throughput password management and direct peer-to-peer sync.

Both applications share the exact same cryptographic specifications and container formats, allowing completely offline cross-device synchronization over your trusted local network.

---

## License

This project is licensed under the terms specified in the repository. Review the license file for details.
