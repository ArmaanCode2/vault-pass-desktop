# Releasing VaultPass Desktop

Installed copies of VaultPass only install an update whose `.sig` file verifies with the public key built into them. A release published without a signature, or signed with another key, is refused. How the check works is described in [SECURITY_ARCHITECTURE.md](SECURITY_ARCHITECTURE.md#9-signed-updates).

Releases are built and signed with `build-installer.ps1` only. It runs the signer, `tools\SignInstaller.java`, as its own `java` process with the JDK 21 it found. The signer asks for the keystore password at a hidden console prompt, so the key is decrypted only inside that process: never in Gradle, never through an environment variable or a command-line argument. Run the script from a normal PowerShell window.

You need Inno Setup 6, a JDK 21 with `jpackage` and your update signing key: a PKCS12 file with an EC P-256 key under the alias `update`, created with

```powershell
keytool -genkeypair -keystore vaultpass-desktop-update.p12 -storetype PKCS12 -alias update -keyalg EC -groupname secp256r1
```

Keep the `.p12` file and its password outside this repository, and keep an offline backup. If the key is lost, installed apps can't accept any further update until users install a build with a new key by hand.

## One time: put the public key into the app

1. Run

   ```powershell
   .\build-installer.ps1 -SigningKeystore D:\path\to\vaultpass-desktop-update.p12
   ```

   and enter the keystore password when asked. Because `src\main\resources\update-signing-key.pem` doesn't exist yet, the signer writes it from your key, prints the SHA-256 of the public key (the SubjectPublicKeyInfo DER, not a certificate fingerprint), signs nothing and the script stops. That is expected.
2. Write the printed public key SHA-256 down next to your key backup. It identifies the trust anchor every installed app carries. Every later signing run prints it again; if it ever differs from your record, stop and find out why before publishing anything.
3. Commit `src\main\resources\update-signing-key.pem`. It holds only the public key. `.gitignore` ignores every other `.pem`, `.p12` and `.pfx` file. Anyone reviewing the commit can check the file against your recorded value:

   ```powershell
   $b64 = (Get-Content src\main\resources\update-signing-key.pem | Where-Object { $_ -notmatch '-----' }) -join ''
   -join ([Security.Cryptography.SHA256]::Create().ComputeHash([Convert]::FromBase64String($b64)) | ForEach-Object { $_.ToString('x2') })
   ```
4. Don't publish the installer from step 1. It was built before the key was in the app, so that build would refuse every future update. Run the script again (next section): the app is rebuilt with the key inside and then signed.

## Every release

1. Set the new version in `version.txt` (for example `1.0.2`). Bump it for every signed build: never sign two different installers with the same version. Versions are `major.minor.patch` in digits only. Never sign test, pre-release or experimental builds with the release key; the signer refuses versions such as `1.0.2-beta`, and installed apps never treat them as updates.

   Before it deletes or builds anything, `build-installer.ps1` checks the version:
   - `version.txt` isn't a version like `1.0.2`: the script stops with exit code 4.
   - A signed installer for this version or a higher one is already in `dist\` or in the `launch` folder next to the repository (a `VaultPass-Setup-v<x.y.z>.exe.sig` file, checked by name only): the script stops with exit code 3. If that version was never published or installed anywhere, you can delete its `.exe`, `.sig` and `.sha256` instead of raising the number.
   - `version.txt` isn't higher than every release on GitHub (`ArmaanCode2/vault-pass-desktop`, tags like `1.0.2` or `v1.0.2`, pre-releases included; other tags are ignored): the script stops with exit code 3 and names the newest release.
   - GitHub can't be asked (no network, rate limit, server error): the script warns and goes on. Check the version yourself before publishing.

   `release.cmd` in the `launch` folder prints what to fix for exit codes 3 and 4.
2. Build and sign:

   ```powershell
   .\build-installer.ps1 -SigningKeystore D:\path\to\vaultpass-desktop-update.p12
   ```

   You can set `VAULTPASS_UPDATE_KEYSTORE` to the `.p12` path (only the path) instead of passing `-SigningKeystore`. The script checks that the keystore exists before it builds anything. The signer then refuses to sign unless:
   - the key is EC P-256 and is the key in `update-signing-key.pem`;
   - the installer is named `VaultPass-Setup-v<version.txt>.exe`;
   - the app image the installer was built from (`build\compose\binaries\main\app\VaultPass\app\*.jar`) contains that same `update-signing-key.pem` and this version, so an installer whose app has no key is never signed;
   - no `.sig` exists for it yet.

   After signing it verifies the new `.sig` by running the app's own `UpdateSignatureVerifier` from the built app image with the key embedded there, and deletes the files if that check fails.
3. Create a GitHub release with the tag `v1.0.2` (the tag is `v` plus `version.txt`) and upload the three files the script lists:
   - `dist\VaultPass-Setup-v1.0.2.exe`
   - `dist\VaultPass-Setup-v1.0.2.exe.sig`
   - `dist\VaultPass-Setup-v1.0.2.exe.sha256`

   Don't rename them, and upload each installer only under its own version's tag. The signature covers the installer's file name (and so its version) together with its SHA-256, and the app only accepts the asset named exactly `VaultPass-Setup-v<tag version>.exe`, so an installer under another name, or an older signed installer re-uploaded under a newer tag, is rejected.

Without `-SigningKeystore`, the script still builds the installer but warns that it is unsigned. Installed apps will refuse to update to it, so don't publish it.

## Test an update before publishing it

An installed copy can look for updates in another GitHub repository instead of `ArmaanCode2/vault-pass-desktop`:

1. Create a test repository (for example `<you>/vaultpass-update-test`) and publish the signed release there, with the same tag and the same three files.
2. Start the installed app with the system property `vaultpass.update.repo`:

   ```powershell
   $env:JAVA_TOOL_OPTIONS = "-Dvaultpass.update.repo=<you>/vaultpass-update-test"
   & "<install folder>\VaultPass.exe"
   ```

3. Check for updates in Settings > About, install, and confirm the app works on the new version. Then publish the release in the real repository.

The value must look like `owner/name` (letters, digits and `-` in the owner; letters, digits, `.`, `_` and `-` in the name); anything else is ignored and the real repository is used. Signature checks are the same as for the real repository, so the test release must be signed with the release key.

## How installed copies install an update

The app downloads the installer and its `.sig` into `data\updates` in its own data folder and checks the signature. It then starts the installer with `/SILENT /NORESTART /LOG="<data>\updates\setup-<version>.log" /DIR="<install folder>"`:

- **Restart now** also passes `/RELAUNCH=1`. The installer opens VaultPass again when it has finished. If the update fails before Setup has copied its files, Setup starts the version that is still installed, and that version says "The update to X.Y.Z didn't install." with a link to the Setup log. If it fails after the old files were removed, Setup opens again, interactive and without `/RELAUNCH=1`, so the user can finish the installation.
- **Later** installs the update when the user closes VaultPass, without `/RELAUNCH=1`, so VaultPass stays closed. It never starts while Windows is shutting down or signing out; in that case the next start says the update wasn't installed.

Only a copy installed by Setup updates itself: the install folder Setup registered must be the folder the app runs from. Any other copy says "This copy of VaultPass wasn't installed by its Setup program, so it can't update itself. Download the installer from GitHub instead." Test updates with an installed copy.

The first update from 1.1.0 won't reopen the app by itself: 1.1.0's updater starts the installer without `/RELAUNCH=1`. Users open VaultPass from the Start menu after that update. Every update after it reopens the app.

## Protect the release channel

The signature stops a changed installer, but the GitHub account still decides what is published. Recommended:

- Turn on GitHub **immutable releases** for the repository, so a published release's assets and tag can't be replaced afterwards.
- Use a **hardware security key** (FIDO2/WebAuthn) as the account's two-factor method, rather than SMS or an authenticator code.

Copies of VaultPass released before signed updates (1.0.1 and older) don't check signatures, so they update to a signed release as before. Every later update is checked by the new updater.
