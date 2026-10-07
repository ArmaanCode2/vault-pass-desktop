package com.vaultpass.desktop.data.update

import com.vaultpass.desktop.domain.models.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException

/**
 * Update checks against stubbed release lookups and downloads. Nothing here touches the network.
 */
class UpdateManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun testSigningKey(): java.security.PublicKey =
        java.security.KeyPairGenerator.getInstance("EC")
            .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public

    /** Answers release lookups from lambdas and records the URLs it was asked for. */
    private class FakeReleaseSource(
        private val api: (String) -> ReleaseApiResponse,
        private val redirect: (String) -> String? = { throw AssertionError("The web fallback was not expected") }
    ) : ReleaseSource {
        val apiUrls = mutableListOf<String>()
        val webUrls = mutableListOf<String>()

        override fun fetchLatestRelease(url: String): ReleaseApiResponse {
            apiUrls += url
            return api(url)
        }

        override fun latestReleaseRedirect(url: String): String? {
            webUrls += url
            return redirect(url)
        }
    }

    /** A fetcher whose every request fails to connect, like an unreachable host. */
    private class RefusingFetcher : UpdateFetcher {
        val requested = mutableListOf<String>()

        override fun fetchBytes(url: String, maxBytes: Long): ByteArray {
            requested += url
            throw ConnectException("Connection refused")
        }

        override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
            requested += url
            throw ConnectException("Connection refused")
        }
    }

    private class NoLaunch : InstallerLauncher {
        val launched = mutableListOf<File>()
        override fun launchAndExit(installerFile: File, arguments: List<String>) {
            launched += installerFile
        }
    }

    private fun manager(
        source: ReleaseSource = FakeReleaseSource({ throw AssertionError("No release lookup was expected") }),
        fetcher: UpdateFetcher = RefusingFetcher(),
        launcher: InstallerLauncher = NoLaunch()
    ) = UpdateManager(
        currentVersion = "1.0.0",
        repoOwner = "o",
        repoName = "r",
        signingPublicKey = testSigningKey(),
        downloadDir = tempDir,
        fetcher = fetcher,
        installerLauncher = launcher,
        releaseSource = source,
        setupRegistration = { com.vaultpass.desktop.data.platform.AppStorageConfig.appInstallationDirectory.absolutePath }
    )

    private fun releaseJson(tag: String) = """
        {
            "tag_name": "$tag",
            "body": "Notes",
            "assets": [
                { "name": "VaultPass-Setup-$tag.exe", "browser_download_url": "https://github.com/o/r/releases/download/$tag/VaultPass-Setup-$tag.exe", "size": 1000 },
                { "name": "VaultPass-Setup-$tag.exe.sig", "browser_download_url": "https://github.com/o/r/releases/download/$tag/VaultPass-Setup-$tag.exe.sig", "size": 100 }
            ]
        }
    """.trimIndent()

    @Test
    fun testSemanticVersionComparison() {
        // Newer versions
        assertTrue(UpdateManager.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(UpdateManager.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(UpdateManager.isNewerVersion("1.0.0", "2.0.0"))
        assertTrue(UpdateManager.isNewerVersion("1.9.9", "1.10.0"), "Numeric, not string, comparison")
        assertTrue(UpdateManager.isNewerVersion("1.0.0", "99999999999999999999.0.0"))

        // Older or equal versions
        assertFalse(UpdateManager.isNewerVersion("1.0.1", "1.0.0"))
        assertFalse(UpdateManager.isNewerVersion("1.1.0", "1.0.9"))
        assertFalse(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(UpdateManager.isNewerVersion("1.10.0", "1.9.9"))
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "1.0.0"))
    }

    @Test
    fun preReleaseAndOddVersionsAreNeverAnUpdate() {
        val odd = listOf(
            "2.0.0-beta", "2.0.0-rc.1", "2.0.0+build.5", "2.0", "2", "2.0.0.1", "v2.0.0", " 2.0.0", "2.0.0 ",
            "2.0.0\n", "２.0.0", "2..0", "2.0.", "", "-1.0.0", "2.0.0/../../x", "2.0.0\\..\\x"
        )
        for (latest in odd) {
            assertFalse(UpdateManager.isNewerVersion("1.0.0", latest), "Accepted latest version \"$latest\"")
            assertFalse(UpdateManager.isSafeVersion(latest), "\"$latest\" is not a release version")
        }
        // An unparseable current version never updates either.
        assertFalse(UpdateManager.isNewerVersion("1.0.0-dev", "2.0.0"))
        assertFalse(UpdateManager.isNewerVersion("v1.0.0", "2.0.0"))
    }

    @Test
    fun testGitHubReleaseJsonDeserialization() {
        val sampleJson = """
            {
                "tag_name": "v1.0.1",
                "name": "VaultPass v1.0.1",
                "body": "Bug fixes and performance improvements",
                "published_at": "2026-09-10T10:00:00Z",
                "assets": [
                    {
                        "name": "VaultPass-Setup-v1.0.1.exe",
                        "browser_download_url": "https://github.com/ArmaanCode2/vault-pass-desktop/releases/download/v1.0.1/VaultPass-Setup-v1.0.1.exe",
                        "size": 100925440
                    },
                    {
                        "name": "checksums.txt",
                        "browser_download_url": "https://github.com/ArmaanCode2/vault-pass-desktop/releases/download/v1.0.1/checksums.txt",
                        "size": 256
                    }
                ]
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val release = json.decodeFromString<GitHubRelease>(sampleJson)

        assertEquals("v1.0.1", release.tagName)
        assertEquals("VaultPass v1.0.1", release.name)
        assertEquals("Bug fixes and performance improvements", release.releaseNotes)
        assertEquals(2, release.assets.size)

        val exeAsset = release.assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
        assertNotNull(exeAsset)
        assertEquals("VaultPass-Setup-v1.0.1.exe", exeAsset?.name)
        assertEquals(100925440L, exeAsset?.size)
    }

    @Test
    fun testAppSettingsAutoCheckForUpdatesDefault() {
        val settings = AppSettings()
        assertTrue(settings.autoCheckForUpdates)

        val modified = settings.copy(autoCheckForUpdates = false)
        assertFalse(modified.autoCheckForUpdates)
    }

    @Test
    fun noPublishedReleaseReturnsUpToDate() = runTest {
        val source = FakeReleaseSource({ ReleaseApiResponse(404, "{\"message\":\"Not Found\"}") })
        val updateManager = manager(source)

        val result = updateManager.checkForUpdates()

        assertTrue(result is UpdateState.UpToDate, "Expected UpdateState.UpToDate on 404, got $result")
        assertEquals(listOf("https://api.github.com/repos/o/r/releases/latest"), source.apiUrls)
    }

    @Test
    fun newerReleaseFromApiIsAvailableWithItsSignatureUrl() = runTest {
        val updateManager = manager(FakeReleaseSource({ ReleaseApiResponse(200, releaseJson("v2.0.0")) }))

        val result = updateManager.checkForUpdates() as UpdateState.Available

        assertEquals("2.0.0", result.version)
        assertEquals("https://github.com/o/r/releases/download/v2.0.0/VaultPass-Setup-v2.0.0.exe", result.downloadUrl)
        assertEquals("https://github.com/o/r/releases/download/v2.0.0/VaultPass-Setup-v2.0.0.exe.sig", result.signatureUrl)
    }

    @Test
    fun preReleaseTagFromApiIsNoUpdate() = runTest {
        val updateManager = manager(FakeReleaseSource({ ReleaseApiResponse(200, releaseJson("v2.0.0-beta.1")) }))

        assertTrue(updateManager.checkForUpdates() is UpdateState.UpToDate)
    }

    @Test
    fun checkConnectionFailureReportsTheConnectionError() = runTest {
        val updateManager = manager(FakeReleaseSource({ throw ConnectException("Connection refused") }))

        assertEquals(UpdateState.Error("Update check failed: Connection refused"), updateManager.checkForUpdates())
    }

    @Test
    fun testDownloadConnectionFailureEmitsCleanErrorStateWithoutCrashing() = runTest {
        val fetcher = RefusingFetcher()
        val updateManager = manager(fetcher = fetcher)

        val available = UpdateState.Available(
            version = "2.0.0",
            releaseNotes = "",
            downloadUrl = "https://updates.example.test/VaultPass-Setup-v2.0.0.exe",
            fileSize = 1000L,
            signatureUrl = "https://updates.example.test/VaultPass-Setup-v2.0.0.exe.sig"
        )

        val file = updateManager.downloadAndPrepareUpdate(available)
        assertNull(file)
        // It failed because the https request could not connect, not because of a URL or signature check.
        assertEquals(listOf(available.signatureUrl), fetcher.requested)
        assertEquals(UpdateState.Error("Download failed: Connection refused"), updateManager.updateState.value)
        assertFalse(File(tempDir, "VaultPass-Setup-v2.0.0.exe").exists())

        updateManager.resetState()
        assertEquals(UpdateState.Idle, updateManager.updateState.value)
    }

    @Test
    fun testLaunchInstallerMissingFileSetsErrorState() {
        val launcher = NoLaunch()
        val updateManager = manager(launcher = launcher)
        val missingFile = File(tempDir, "DefinitelyNonExistentSetupFile12345.exe")

        updateManager.launchInstallerAndExit(missingFile)

        val err = updateManager.updateState.value as UpdateState.Error
        assertTrue(err.message.contains("Installer file not found"))
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun rateLimitedApiFallsBackToWebRedirectWithSignatureUrlNextToInstaller() = runTest {
        val source = FakeReleaseSource(
            api = { ReleaseApiResponse(403, "rate limited") },
            redirect = { "https://github.com/o/r/releases/tag/v2.0.0" }
        )
        val updateManager = manager(source)

        val result = updateManager.checkForUpdates() as UpdateState.Available

        assertEquals(listOf("https://github.com/o/r/releases/latest"), source.webUrls)
        assertEquals("2.0.0", result.version)
        assertEquals("https://github.com/o/r/releases/download/v2.0.0/VaultPass-Setup-v2.0.0.exe", result.downloadUrl)
        assertEquals("https://github.com/o/r/releases/download/v2.0.0/VaultPass-Setup-v2.0.0.exe.sig", result.signatureUrl)
        assertNull(result.expectedSha256)

        // A tag without the "v" prefix keeps its own name in the download path.
        val plainTag = manager(FakeReleaseSource({ ReleaseApiResponse(403, "") }, { "https://github.com/o/r/releases/tag/2.0.0" }))
        val plain = plainTag.checkForUpdates() as UpdateState.Available
        assertEquals("https://github.com/o/r/releases/download/2.0.0/VaultPass-Setup-v2.0.0.exe.sig", plain.signatureUrl)
    }

    @Test
    fun webFallbackIgnoresPreReleaseAndUnsafeTags() {
        for (tag in listOf("v2.0.0-beta", "v2.0.0/../../evil", "v2.0", "latest")) {
            val updateManager = manager(FakeReleaseSource({ ReleaseApiResponse(403, "") }, { "https://github.com/o/r/releases/tag/$tag" }))
            val state = updateManager.checkUpdatesViaWebFallback()
            assertTrue(state is UpdateState.UpToDate, "Tag $tag gave $state")
        }
    }

    @Test
    fun webFallbackWithoutTagRedirectIsUpToDate() {
        val updateManager = manager(FakeReleaseSource({ ReleaseApiResponse(403, "") }, { null }))

        assertTrue(updateManager.checkUpdatesViaWebFallback() is UpdateState.UpToDate)
    }

    @Test
    fun webFallbackConnectionFailureReportsOffline() {
        val updateManager = manager(FakeReleaseSource({ ReleaseApiResponse(403, "") }, { throw ConnectException("Connection refused") }))

        assertEquals(UpdateState.Error(UpdateManager.WEB_FALLBACK_OFFLINE_MESSAGE), updateManager.checkUpdatesViaWebFallback())
    }
}
