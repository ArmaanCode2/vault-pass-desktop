# Privacy Policy for VaultPass Desktop

**Effective Date:** September 10, 2026

## 1. Introduction
Welcome to VaultPass Desktop. We respect your privacy and are committed to protecting it. This Privacy Policy explains our practices regarding data privacy, local storage, and network communication when you use the VaultPass Desktop application ("the Application").

VaultPass Desktop is engineered from the ground up as a local-first, zero-cloud password manager. We believe your private credentials belong exclusively to you. The Application operates entirely on your personal machine and does not require a user account, cloud subscription, or continuous internet connection to function.

---

## 2. Information Stored by the Application
VaultPass Desktop allows you to manage sensitive information, including account logins, passwords, usernames, web URLs, secure notes, and custom fields.

* **Zero Access:** We do not collect, transmit, store, or have access to any of this information.
* **No Accounts or Registration:** VaultPass Desktop requires no user registration, email address, phone number, or personal identifiers.
* **No Telemetry or Analytics:** The Application contains no tracking code, telemetry SDKs, analytics suites, or behavioral monitoring tools. We do not track how you use the Application.

---

## 3. Local Device Storage
All data managed by VaultPass Desktop is stored exclusively on your local computer storage.

* **No Developer Cloud Infrastructure:** We do not operate cloud databases, sync servers, or proprietary relay backends. Your data never touches any server owned or operated by us.
* **Storage Location:** All encrypted vault databases, encryption metadata, and application configurations reside locally on your computer (within your chosen installation folder's `data` directory or your user application directory).
* **Zero Telemetry or Crash Reports:** Crash traces and operational logs remain local to your machine and are never transmitted automatically to us or any third party.

---

## 4. Cryptography and Security Architecture
All vault records are secured using industry-standard cryptography before being written to disk.

* **Master Password:** Your data is encrypted using keys derived from your Master Password via Argon2id. Your Master Password is never transmitted off your machine and is never stored in plain text.
* **Session Protection:** Sensitive cryptographic keys in system memory are protected using Windows Data Protection API (DPAPI) and are zeroed out when the vault is locked.
* **No Recovery Backdoors:** Because encryption occurs locally and we have zero knowledge of your Master Password, **we cannot reset your password or recover your data if you lose your Master Password.**
* **Brute-Force Protection:** The Application incorporates rate-limiting cooldown timers to resist local brute-force attempts.

---

## 5. Network Communication and Software Updates
VaultPass Desktop maintains a strict separation between your local encrypted vault and external network requests. The Application has two specific, isolated networking capabilities:

### A. Software Update Checks (GitHub Releases)
To protect users with timely security patches and feature updates, VaultPass Desktop includes an optional software update checker.
* **Public Endpoint Queried:** When checking for updates, the Application sends a standard HTTPS `GET` request to GitHub's public API (`https://api.github.com/repos/ArmaanCode2/vault-pass-desktop/releases/latest`) and, if approved by the user, downloads updated installation packages from GitHub Releases.
* **What Data is Sent:** In accordance with standard Internet protocol communication, this request transmits only generic technical network headers (your IP address as visible to GitHub, and a standard User-Agent header such as `VaultPass-Desktop/1.0.0`).
* **What Data is NEVER Sent:** Vault records, master passwords, encryption keys, usernames, URLs, machine identifiers, hardware fingerprints, and usage statistics are **never** included in update requests.
* **Third-Party Infrastructure:** Update binaries and release metadata are hosted on GitHub (Microsoft Corporation) and are subject to the [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement). VaultPass operates no intermediate proxy or custom distribution server.
* **User Control & Air-Gapped Operation:** Automatic update checks can be toggled off at any time under **Settings -> About**. VaultPass Desktop operates with complete feature parity in air-gapped or firewall-blocked environments. If network connectivity is unavailable, the update check silently ignores the connection without affecting vault operations.

### B. Local Network Synchronization (LAN Sync)
VaultPass Desktop can synchronize credentials directly with the **VaultPass Android** companion app.
* **Local Subnet Only:** Synchronization occurs directly between devices over your local area network (Wi-Fi) using end-to-end encrypted sockets.
* **Zero Cloud Relays:** Sync data is never transmitted over the public internet, external proxies, or third-party relay servers. Both devices must reside on the same local subnet to communicate.

---

## 6. Import and Export Features
VaultPass Desktop provides tools to import credentials from external formats (CSV, Bitwarden, KeePass) and export your vault data (unencrypted CSV/JSON or encrypted backups).
* Exported files are written directly to the local directory path chosen by you.
* You are solely responsible for securing exported files. Exporting in plain text formats exposes your credentials to anyone with access to that file.

---

## 7. Clipboard Security
When you copy a username, password, or note to your system clipboard:
* VaultPass Desktop places the data on the OS clipboard for pasting into other applications.
* The Application includes an automatic clipboard clearing timer (configurable in Settings) to minimize exposure to other local desktop applications that monitor clipboard activity.

---

## 8. Third-Party Services
* **No Advertisements:** VaultPass Desktop contains no advertisements or advertising SDKs.
* **No Analytics:** VaultPass Desktop contains no analytics, usage tracking, or user profiling services.
* **No Data Brokerage:** Because we do not collect or possess your information, we do not (and cannot) sell, lease, or distribute your data to any third party.

---

## 9. Data Retention and Deletion
Because your data is stored exclusively on your computer, you have complete control over data retention and deletion.
* To delete all stored credentials and configuration files, delete the `data` directory in your VaultPass installation folder or uninstall the application and remove the data files.
* Soft-deleted items in the Application's Recycle Bin are automatically purged after 7 days, or can be permanently deleted manually at any time.

---

## 10. Privacy Rights (GDPR & CCPA Compliance)
Under regulations such as the General Data Protection Regulation (GDPR) and the California Consumer Privacy Act (CCPA), individuals have rights regarding access, rectification, portability, and erasure of personal information.

Because VaultPass Desktop does not collect, process, or transmit your personal data to any server, you already maintain direct, autonomous sovereignty over your data:
* **Access & Portability:** You can view, search, and export all stored records at any time.
* **Rectification:** You can edit or update any entry directly in the vault interface.
* **Erasure:** You can immediately and irrevocably delete any credential, folder, or the entire vault file directly on your local system.

---

## 11. User Security Responsibilities
The privacy and security of your vault also rely on your local computing practices. By using VaultPass Desktop, you acknowledge responsibility for:
* Selecting a strong, complex Master Password.
* Safeguarding and memorizing your Master Password, as it cannot be recovered by anyone if lost.
* Maintaining physical and administrative security over your personal computer.
* Protecting any unencrypted export files created on your local disk.
* Keeping your operating system secure against malware, keyloggers, and unauthorized physical access.

---

## 12. Changes to This Privacy Policy
We may update this Privacy Policy periodically to reflect enhancements in application features or security practices. Because we do not collect contact details, updates are published directly in the open-source repository. We encourage users to review this document as new releases are issued.

---

## 13. Contact Information
If you have questions, inquiries, or feedback concerning this Privacy Policy or the security architecture of VaultPass Desktop, please contact us:
* **GitHub Issues:** [https://github.com/ArmaanCode2/vault-pass-desktop](https://github.com/ArmaanCode2/vault-pass-desktop)
* **Email:** armaanweb100@gmail.com
