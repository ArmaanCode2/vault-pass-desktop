package com.vaultpass.desktop.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val releaseNotes: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("size") val size: Long,
    /** GitHub's checksum of the uploaded file, e.g. "sha256:<hex>". Absent on older releases. */
    @SerialName("digest") val digest: String? = null
)

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(
        val version: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val fileSize: Long,
        /** URL of the installer's detached signature ("<installer>.sig"). */
        val signatureUrl: String,
        /** Lower-case hex SHA-256 GitHub reported for the installer, when it reported one. */
        val expectedSha256: String? = null
    ) : UpdateState
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UpdateState
    /** The download finished and its checksum and signature are being checked. */
    data class Verifying(val version: String) : UpdateState
    data class ReadyToInstall(
        val installerFile: File,
        val version: String
    ) : UpdateState
    data class Error(val message: String) : UpdateState
    /**
     * An update to [version] was started or chosen earlier, but this copy is still older. [logFile] is
     * Setup's log; [setupRan] is false when Setup never wrote it ("Later", then Windows shut down).
     */
    data class UpdateFailed(val version: String, val logFile: File, val setupRan: Boolean) : UpdateState
}

/** `<updates>\pending-update.json`, written just before an installer is started. */
@Serializable
data class PendingUpdateMarker(
    val version: String,
    val log: String
)
