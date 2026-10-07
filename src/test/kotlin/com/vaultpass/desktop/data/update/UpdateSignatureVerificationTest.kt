package com.vaultpass.desktop.data.update

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UpdateSignatureVerificationTest {

    @TempDir
    lateinit var tempDir: File

    private val installerName = "VaultPass-Setup-v2.0.0.exe"
    private val installerUrl = "https://example.test/download/v2.0.0/$installerName"
    private val signatureUrl = "$installerUrl.sig"
    private val installerBytes = ByteArray(200_000) { (it * 31 % 251).toByte() }

    private fun ecKeyPair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    private companion object {
        /** Base64 of DER SEQUENCE { INTEGER 0, INTEGER 0 }. */
        const val ZERO_SIGNATURE = "MAYCAQACAQA="
    }

    /** Signs the release message for [bytes] published as [fileName], as tools/SignInstaller.java does. */
    private fun sign(privateKey: PrivateKey, bytes: ByteArray, fileName: String = installerName): String =
        signRaw(privateKey, UpdateSignatureVerifier.signedMessage(fileName, sha256Hex(bytes)))

    private fun signRaw(privateKey: PrivateKey, message: ByteArray): String {
        val signer = Signature.getInstance(UpdateSignatureVerifier.SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(message)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun toPem(keyPair: KeyPair): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves in-memory release files; anything not in [files] is a 404. */
    private class FakeFetcher(private val files: Map<String, ByteArray>) : UpdateFetcher {
        override fun fetchBytes(url: String, maxBytes: Long): ByteArray {
            val bytes = files[url] ?: throw UpdateHttpException(404)
            if (bytes.size > maxBytes) throw UpdateRejectedException(HttpsUpdateFetcher.TOO_LARGE_MESSAGE)
            return bytes
        }

        override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
            val bytes = files[url] ?: throw UpdateHttpException(404)
            if (bytes.size > maxBytes) throw UpdateRejectedException(HttpsUpdateFetcher.TOO_LARGE_MESSAGE)
            destination.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private class RecordingLauncher : InstallerLauncher {
        val launched = mutableListOf<Pair<File, List<String>>>()
        override fun launchAndExit(installerFile: File, arguments: List<String>) {
            launched += installerFile to arguments
        }
    }

    /** Release lookups for tests that check for updates; by default no lookup is expected. */
    private class FakeReleaseSource(
        private val apiStatus: Int = 0,
        private val redirect: String? = null
    ) : ReleaseSource {
        override fun fetchLatestRelease(url: String): ReleaseApiResponse {
            if (apiStatus == 0) throw AssertionError("No release lookup was expected")
            return ReleaseApiResponse(apiStatus, "")
        }

        override fun latestReleaseRedirect(url: String): String? = redirect
    }

    private fun manager(
        keyPair: KeyPair?,
        files: Map<String, ByteArray>,
        launcher: InstallerLauncher = RecordingLauncher(),
        releaseSource: ReleaseSource = FakeReleaseSource()
    ) = UpdateManager(
        currentVersion = "1.0.0",
        signingPublicKey = keyPair?.public,
        downloadDir = tempDir,
        fetcher = FakeFetcher(files),
        installerLauncher = launcher,
        releaseSource = releaseSource,
        setupRegistration = { com.vaultpass.desktop.data.platform.AppStorageConfig.appInstallationDirectory.absolutePath }
    )

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun available(expectedSha256: String? = null) = UpdateState.Available(
        version = "2.0.0",
        releaseNotes = "",
        downloadUrl = installerUrl,
        fileSize = installerBytes.size.toLong(),
        signatureUrl = signatureUrl,
        expectedSha256 = expectedSha256
    )

    private fun downloadedFile() = File(tempDir, "VaultPass-Setup-v2.0.0.exe")

    private fun assertRejected(manager: UpdateManager, result: File?, expectedMessage: String) {
        assertNull(result)
        val state = manager.updateState.value
        assertTrue(state is UpdateState.Error, "Expected an error, got $state")
        assertEquals(expectedMessage, (state as UpdateState.Error).message)
        assertFalse(downloadedFile().exists(), "A rejected download must be deleted")
    }

    @Test
    fun signAndVerifyRoundTrip() {
        val keyPair = ecKeyPair()
        val file = File(tempDir, "installer.exe").apply { writeBytes(installerBytes) }
        val signature = sign(keyPair.private, installerBytes)

        assertTrue(UpdateSignatureVerifier.verifyFile(keyPair.public, file, installerName, signature))
        assertTrue(UpdateSignatureVerifier.verifyFile(keyPair.public, file, installerName, "$signature\r\n"), "Trailing newline is allowed")
        val parsed = UpdateSignatureVerifier.parsePublicKeyPem(toPem(keyPair))
        assertArrayEquals(keyPair.public.encoded, parsed.encoded)
        assertTrue(UpdateSignatureVerifier.verifyFile(parsed, file, installerName, signature))
    }

    @Test
    fun signedMessageHasTheDocumentedFormat() {
        val message = UpdateSignatureVerifier.signedMessage("VaultPass-Setup-v1.2.3.exe", "AB".repeat(32))
        assertEquals("VaultPass-Desktop-Update-v1\nVaultPass-Setup-v1.2.3.exe\n" + "ab".repeat(32), message.toString(Charsets.UTF_8))
    }

    @Test
    fun olderSignedInstallerRepublishedUnderNewerTagIsRejected() = runTest {
        val keyPair = ecKeyPair()
        // A genuine, correctly signed 1.0.0 installer, uploaded again as the 2.0.0 release.
        val olderSignature = sign(keyPair.private, installerBytes, fileName = "VaultPass-Setup-v1.0.0.exe")
        val file = File(tempDir, "installer.exe").apply { writeBytes(installerBytes) }
        assertTrue(UpdateSignatureVerifier.verifyFile(keyPair.public, file, "VaultPass-Setup-v1.0.0.exe", olderSignature))

        val manager = manager(keyPair, mapOf(installerUrl to installerBytes, signatureUrl to olderSignature.toByteArray()))
        assertRejected(manager, manager.downloadAndPrepareUpdate(available(expectedSha256 = sha256Hex(installerBytes))), UpdateManager.BAD_SIGNATURE_MESSAGE)
    }

    @Test
    fun signatureForAnotherVersionNameIsRejected() {
        val keyPair = ecKeyPair()
        val file = File(tempDir, "installer.exe").apply { writeBytes(installerBytes) }
        val signature = sign(keyPair.private, installerBytes)

        for (wrongName in listOf("VaultPass-Setup-v2.0.1.exe", "VaultPass-Setup-v2.0.0.EXE", "VaultPass-Setup-v2.0.exe", "installer.exe")) {
            assertFalse(UpdateSignatureVerifier.verifyFile(keyPair.public, file, wrongName, signature), "Accepted name $wrongName")
        }
        // A signature over the raw installer bytes (no bound name) is not accepted either.
        assertFalse(UpdateSignatureVerifier.verifyFile(keyPair.public, file, installerName, signRaw(keyPair.private, installerBytes)))
    }

    @Test
    fun verifiedDownloadBecomesReadyAndLaunchesSilently() = runTest {
        val keyPair = ecKeyPair()
        val launcher = RecordingLauncher()
        val files = mapOf(installerUrl to installerBytes, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())
        val manager = manager(keyPair, files, launcher)

        val result = manager.downloadAndPrepareUpdate(available(expectedSha256 = sha256Hex(installerBytes)))

        assertNotNull(result)
        val state = manager.updateState.value
        assertTrue(state is UpdateState.ReadyToInstall, "Expected ReadyToInstall, got $state")
        manager.applyUpdate(state as UpdateState.ReadyToInstall)
        assertEquals(listOf(downloadedFile().absoluteFile), launcher.launched.map { it.first.absoluteFile })
        assertTrue("/SILENT" in launcher.launched.single().second, "Launched silently")
    }

    @Test
    fun tamperedInstallerIsRejectedAndDeleted() = runTest {
        val keyPair = ecKeyPair()
        val tampered = installerBytes.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        val files = mapOf(installerUrl to tampered, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())
        val manager = manager(keyPair, files)

        assertRejected(manager, manager.downloadAndPrepareUpdate(available()), UpdateManager.BAD_SIGNATURE_MESSAGE)
    }

    @Test
    fun missingSignatureFileIsRejected() = runTest {
        val manager = manager(ecKeyPair(), mapOf(installerUrl to installerBytes))

        assertRejected(manager, manager.downloadAndPrepareUpdate(available()), UpdateManager.UNSIGNED_RELEASE_MESSAGE)
    }

    @Test
    fun emptySignatureFileIsRejected() = runTest {
        val manager = manager(ecKeyPair(), mapOf(installerUrl to installerBytes, signatureUrl to "\n".toByteArray()))

        assertRejected(manager, manager.downloadAndPrepareUpdate(available()), UpdateManager.UNSIGNED_RELEASE_MESSAGE)
    }

    @Test
    fun signatureFromAnotherKeyIsRejected() = runTest {
        val trusted = ecKeyPair()
        val attacker = ecKeyPair()
        val files = mapOf(installerUrl to installerBytes, signatureUrl to sign(attacker.private, installerBytes).toByteArray())
        val manager = manager(trusted, files)

        assertRejected(manager, manager.downloadAndPrepareUpdate(available()), UpdateManager.BAD_SIGNATURE_MESSAGE)
    }

    @Test
    fun malformedSignaturesAreRejected() = runTest {
        val keyPair = ecKeyPair()
        val file = File(tempDir, "installer.exe").apply { writeBytes(installerBytes) }
        val garbageDer = Base64.getEncoder().encodeToString(ByteArray(70) { 7 })
        val truncated = sign(keyPair.private, installerBytes).dropLast(8)

        for (bad in listOf("not base64 at all!!", garbageDer, truncated, "", ZERO_SIGNATURE)) {
            assertFalse(UpdateSignatureVerifier.verifyFile(keyPair.public, file, installerName, bad), "Accepted malformed signature: $bad")
        }

        val manager = manager(keyPair, mapOf(installerUrl to installerBytes, signatureUrl to garbageDer.toByteArray()))
        assertRejected(manager, manager.downloadAndPrepareUpdate(available()), UpdateManager.BAD_SIGNATURE_MESSAGE)
    }

    @Test
    fun zeroSignatureIsRejected() = runTest {
        // DER SEQUENCE { INTEGER 0, INTEGER 0 }: r = s = 0, the "psychic signature" (CVE-2022-21449).
        assertArrayEquals(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00), Base64.getDecoder().decode(ZERO_SIGNATURE))
        val keyPair = ecKeyPair()
        assertFalse(UpdateSignatureVerifier.verify(keyPair.public, installerName, sha256Hex(installerBytes), ZERO_SIGNATURE))

        val manager = manager(keyPair, mapOf(installerUrl to installerBytes, signatureUrl to ZERO_SIGNATURE.toByteArray()))
        assertRejected(manager, manager.downloadAndPrepareUpdate(available()), UpdateManager.BAD_SIGNATURE_MESSAGE)
    }

    @Test
    fun githubDigestMismatchIsRejected() = runTest {
        val keyPair = ecKeyPair()
        val files = mapOf(installerUrl to installerBytes, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())
        val manager = manager(keyPair, files)
        val wrongDigest = UpdateManager.parseSha256Digest("sha256:" + "0".repeat(64))

        assertRejected(manager, manager.downloadAndPrepareUpdate(available(wrongDigest)), UpdateManager.CHECKSUM_MISMATCH_MESSAGE)

        val malformedDigest = UpdateManager.parseSha256Digest("sha256:xyz")
        assertRejected(manager, manager.downloadAndPrepareUpdate(available(malformedDigest)), UpdateManager.CHECKSUM_MISMATCH_MESSAGE)
    }

    @Test
    fun installerChangedAfterVerificationIsNotLaunched() = runTest {
        val keyPair = ecKeyPair()
        val launcher = RecordingLauncher()
        val files = mapOf(installerUrl to installerBytes, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())
        val manager = manager(keyPair, files, launcher)

        manager.downloadAndPrepareUpdate(available())
        val ready = manager.updateState.value as UpdateState.ReadyToInstall
        ready.installerFile.writeBytes(installerBytes + byteArrayOf(1, 2, 3))

        manager.applyUpdate(ready)

        assertTrue(launcher.launched.isEmpty(), "A changed installer must never be launched")
        assertEquals(UpdateState.Error(UpdateManager.CHANGED_AFTER_CHECK_MESSAGE), manager.updateState.value)
        assertFalse(ready.installerFile.exists())
    }

    @Test
    fun installerOverwrittenWithArbitraryBytesIsNotLaunched() = runTest {
        val keyPair = ecKeyPair()
        val launcher = RecordingLauncher()
        val files = mapOf(installerUrl to installerBytes, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())
        val manager = manager(keyPair, files, launcher)

        manager.downloadAndPrepareUpdate(available())
        val ready = manager.updateState.value as UpdateState.ReadyToInstall
        // Unsigned bytes written over the checked file.
        ready.installerFile.writeBytes(byteArrayOf(9, 9, 9))

        manager.launchInstallerAndExit(ready.installerFile, LaunchMode.RestartNow)

        assertTrue(launcher.launched.isEmpty())
        assertEquals(UpdateState.Error(UpdateManager.CHANGED_AFTER_CHECK_MESSAGE), manager.updateState.value)
    }

    @Test
    fun differentValidlySignedInstallerSwappedInIsRefused() = runTest {
        val keyPair = ecKeyPair()
        val otherBytes = ByteArray(150_000) { (it * 17 % 253).toByte() }
        // Genuine installers signed with the trusted key: another 2.0.0 build and an older 1.0.0 release.
        val swaps = listOf(
            installerName to otherBytes,
            "VaultPass-Setup-v1.0.0.exe" to installerBytes.copyOf(100_000)
        )
        for ((swapName, swapBytes) in swaps) {
            val swapSignature = sign(keyPair.private, swapBytes, fileName = swapName)
            val swapFile = File(tempDir, "swap-$swapName").apply { writeBytes(swapBytes) }
            assertTrue(UpdateSignatureVerifier.verifyFile(keyPair.public, swapFile, swapName, swapSignature), "The swapped-in file is validly signed")

            val launcher = RecordingLauncher()
            val files = mapOf(installerUrl to installerBytes, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())
            val manager = manager(keyPair, files, launcher)
            manager.downloadAndPrepareUpdate(available())
            val ready = manager.updateState.value as UpdateState.ReadyToInstall

            swapFile.copyTo(ready.installerFile, overwrite = true)
            manager.launchInstallerAndExit(ready.installerFile, LaunchMode.RestartNow)

            assertTrue(launcher.launched.isEmpty(), "Swapped-in $swapName must not be launched")
            assertEquals(UpdateState.Error(UpdateManager.CHANGED_AFTER_CHECK_MESSAGE), manager.updateState.value)
            assertFalse(ready.installerFile.exists())
        }
    }

    @Test
    fun installerCannotBeChangedOrDeletedWhileItIsBeingLaunched() = runTest {
        assumeTrue(isWindows, "Share-deny handles are Windows only")
        val keyPair = ecKeyPair()
        val attempts = mutableListOf<String>()
        val launcher = InstallerLauncher { file, _ ->
            // Runs while UpdateManager still holds its handle on the verified file.
            attempts += try {
                FileOutputStream(file, true).use { it.write(1) }
                "write succeeded"
            } catch (_: IOException) {
                "write blocked"
            }
            attempts += try {
                Files.delete(file.toPath())
                "delete succeeded"
            } catch (_: IOException) {
                "delete blocked"
            }
        }
        val files = mapOf(installerUrl to installerBytes, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())
        val manager = manager(keyPair, files, launcher)
        manager.downloadAndPrepareUpdate(available())
        val ready = manager.updateState.value as UpdateState.ReadyToInstall

        manager.applyUpdate(ready)

        assertEquals(listOf("write blocked", "delete blocked"), attempts)
        assertArrayEquals(installerBytes, ready.installerFile.readBytes())
    }

    @Test
    fun lockedInstallerCanStillBeExecuted() {
        assumeTrue(isWindows, "Share-deny handles are Windows only")
        val cmd = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\cmd.exe")
        assumeTrue(cmd.isFile, "cmd.exe not found")
        val exe = File(tempDir, "locked-test.exe")
        cmd.copyTo(exe)

        UpdateManager.openInstallerLocked(exe.toPath()).use { channel ->
            assertEquals(UpdateSignatureVerifier.sha256Hex(exe), UpdateSignatureVerifier.sha256Hex(channel))
            assertThrows(IOException::class.java) { FileOutputStream(exe, true).use { it.write(0) } }
            val process = ProcessBuilder(exe.absolutePath, "/d", "/c", "exit 7")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(7, process.exitValue(), "Windows can run the installer while VaultPass holds it locked")
        }
    }

    @Test
    fun webFallbackReleaseDownloadsAndVerifiesItsSignature() = runTest {
        val keyPair = ecKeyPair()
        val base = "https://github.com/ArmaanCode2/vault-pass-desktop/releases/download/v2.0.0/$installerName"
        val files = mapOf(base to installerBytes, "$base.sig" to sign(keyPair.private, installerBytes).toByteArray())
        val source = FakeReleaseSource(apiStatus = 403, redirect = "https://github.com/ArmaanCode2/vault-pass-desktop/releases/tag/v2.0.0")
        val manager = manager(keyPair, files, releaseSource = source)

        val found = manager.checkForUpdates() as UpdateState.Available
        assertEquals("$base.sig", found.signatureUrl)

        assertNotNull(manager.downloadAndPrepareUpdate(found))
        assertTrue(manager.updateState.value is UpdateState.ReadyToInstall)

        // The same release without its .sig is refused as unsigned.
        val unsigned = manager(keyPair, mapOf(base to installerBytes), releaseSource = source)
        val unsignedFound = unsigned.checkForUpdates() as UpdateState.Available
        assertRejected(unsigned, unsigned.downloadAndPrepareUpdate(unsignedFound), UpdateManager.UNSIGNED_RELEASE_MESSAGE)
    }

    @Test
    fun fileThatWasNeverVerifiedIsNotLaunched() {
        val launcher = RecordingLauncher()
        val manager = manager(ecKeyPair(), emptyMap(), launcher)
        val stray = File(tempDir, "something.exe").apply { writeBytes(installerBytes) }

        manager.launchInstallerAndExit(stray, LaunchMode.RestartNow)

        assertTrue(launcher.launched.isEmpty())
        assertEquals(UpdateState.Error(UpdateManager.NOT_VERIFIED_MESSAGE), manager.updateState.value)
        assertTrue(stray.exists(), "Files VaultPass didn't download are left alone")
    }

    @Test
    fun missingEmbeddedKeyDisablesUpdates() = runTest {
        val noResources = object : ClassLoader(null) {}
        assertNull(UpdateSignatureVerifier.loadEmbeddedPublicKey(noResources))

        val launcher = RecordingLauncher()
        val manager = manager(keyPair = null, files = emptyMap(), launcher = launcher)
        val disabled = UpdateState.Error(UpdateManager.UPDATES_DISABLED_MESSAGE)

        assertEquals(disabled, manager.checkForUpdates())
        assertNull(manager.downloadAndPrepareUpdate(available()))
        assertEquals(disabled, manager.updateState.value)

        val file = File(tempDir, "VaultPass-Setup-v2.0.0.exe").apply { writeBytes(installerBytes) }
        manager.launchInstallerAndExit(file, LaunchMode.RestartNow)
        assertEquals(disabled, manager.updateState.value)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun embeddedKeyIsLoadedFromClasspathAndInvalidKeysAreIgnored() {
        fun loaderWith(pem: String): URLClassLoader {
            val dir = File(tempDir, "cp-${System.nanoTime()}").apply { mkdirs() }
            File(dir, UpdateSignatureVerifier.PUBLIC_KEY_RESOURCE).writeText(pem)
            return URLClassLoader(arrayOf(dir.toURI().toURL()), null)
        }

        val keyPair = ecKeyPair()
        loaderWith(toPem(keyPair)).use { assertArrayEquals(keyPair.public.encoded, UpdateSignatureVerifier.loadEmbeddedPublicKey(it)?.encoded) }
        loaderWith("not a key").use { assertNull(UpdateSignatureVerifier.loadEmbeddedPublicKey(it)) }
        loaderWith(toPem(ecKeyPair("secp384r1"))).use { assertNull(UpdateSignatureVerifier.loadEmbeddedPublicKey(it)) }
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        loaderWith(toPem(rsa)).use { assertNull(UpdateSignatureVerifier.loadEmbeddedPublicKey(it)) }
    }

    @Test
    fun installerAndSignatureAssetSelection() {
        fun asset(name: String, digest: String? = null) =
            GitHubAsset(name, "https://github.com/o/r/releases/download/v2.0.0/$name", 100L, digest)

        val manager = manager(ecKeyPair(), emptyMap())
        val exact = asset("VaultPass-Setup-v2.0.0.exe", "sha256:" + "AB".repeat(32))
        val release = GitHubRelease(
            tagName = "v2.0.0",
            assets = listOf(asset("Other-Tool.exe"), exact, asset("VaultPass-Setup-v2.0.0.exe.sig"), asset("Other-Tool.exe.sig"))
        )

        val state = manager.stateForRelease(release, "2.0.0") as UpdateState.Available
        assertEquals(exact.downloadUrl, state.downloadUrl)
        assertEquals(exact.downloadUrl + ".sig", state.signatureUrl)
        assertEquals("ab".repeat(32), state.expectedSha256)

        // No fallback: a release's only .exe under another name, or the right name in other case, is not used.
        for (name in listOf("VaultPass-Installer.exe", "vaultpass-setup-v2.0.0.exe", "VaultPass-Setup-v2.0.0.EXE", "VaultPass-Setup-2.0.0.exe")) {
            val other = GitHubRelease("v2.0.0", assets = listOf(asset(name), asset("$name.sig")))
            val otherState = manager.stateForRelease(other, "2.0.0")
            assertTrue(otherState is UpdateState.Error, "Accepted installer asset $name")
        }
        assertNull(UpdateManager.selectInstallerAsset(listOf(asset("A.exe"), asset("B.exe")), "2.0.0"))

        // Installer without its .sig asset (another file's .sig, or a differently cased one, doesn't count).
        val unsigned = GitHubRelease("v2.0.0", assets = listOf(exact, asset("checksums.txt.sig"), asset("VaultPass-Setup-v2.0.0.exe.SIG")))
        assertEquals(UpdateState.Error(UpdateManager.UNSIGNED_RELEASE_MESSAGE), manager.stateForRelease(unsigned, "2.0.0"))

        assertNull(UpdateManager.parseSha256Digest(null))
        assertNull(UpdateManager.parseSha256Digest("sha512:abcd"))
    }

    @Test
    fun unsafeVersionIsRejectedBeforeAnyFileIsWritten() = runTest {
        val manager = manager(ecKeyPair(), emptyMap())
        val release = GitHubRelease("v2.0.0/../../x", assets = emptyList())

        assertEquals(UpdateState.Error(UpdateManager.INVALID_VERSION_MESSAGE), manager.stateForRelease(release, "2.0.0/../../x"))
        for (version in listOf("..\\..\\evil", "2.0.0-beta", "2.0.0.1", "2.0")) {
            assertNull(manager.downloadAndPrepareUpdate(available().copy(version = version)))
            assertEquals(UpdateState.Error(UpdateManager.INVALID_VERSION_MESSAGE), manager.updateState.value, "Version $version")
            assertEquals(UpdateState.Error(UpdateManager.INVALID_VERSION_MESSAGE), manager.stateForRelease(release, version))
        }
        assertEquals(0, tempDir.listFiles()!!.size, "Nothing was written for an invalid version")
    }

    @Test
    fun fetcherRefusesPlainHttp() {
        val fetcher = HttpsUpdateFetcher(java.net.http.HttpClient.newHttpClient(), "VaultPass-Desktop/test")
        val error = assertThrows(UpdateRejectedException::class.java) {
            fetcher.fetchBytes("http://example.test/VaultPass-Setup-v2.0.0.exe.sig", 1024)
        }
        assertEquals(HttpsUpdateFetcher.HTTPS_ONLY_MESSAGE, error.message)
        assertThrows(UpdateRejectedException::class.java) {
            fetcher.downloadToFile("http://example.test/VaultPass-Setup-v2.0.0.exe", File(tempDir, "x.exe"), 1024) { _, _ -> }
        }
        assertFalse(File(tempDir, "x.exe").exists())
    }
}
