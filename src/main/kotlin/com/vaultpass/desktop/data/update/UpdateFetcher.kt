package com.vaultpass.desktop.data.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The server answered a download with a status other than 200. */
class UpdateHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

/** A download was refused before or while it ran. [message] is shown to the user as is. */
class UpdateRejectedException(message: String) : IOException(message)

/**
 * Downloads release files. Implementations must only use https, including after redirects.
 */
interface UpdateFetcher {
    /** Reads a small file (such as a `.sig`) into memory, refusing anything larger than [maxBytes]. */
    fun fetchBytes(url: String, maxBytes: Long): ByteArray

    /**
     * Streams [url] into [destination], refusing anything larger than [maxBytes].
     * [onProgress] receives (bytes written, Content-Length or 0) after every chunk and may throw to
     * stop the download. Implementations close their streams before any exception leaves, and stop
     * when the thread is interrupted (that is how a download is cancelled).
     */
    fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit)
}

/** Status code and body of a Releases API response. */
data class ReleaseApiResponse(val statusCode: Int, val body: String)

/**
 * Looks up the latest release. Injectable so tests never reach github.com.
 */
interface ReleaseSource {
    /** GETs the Releases API "latest release" endpoint at [url]. */
    fun fetchLatestRelease(url: String): ReleaseApiResponse

    /** Requests the "latest release" web page at [url] without following redirects; returns its Location header, or null. */
    fun latestReleaseRedirect(url: String): String?
}

class HttpsReleaseSource(
    private val httpClient: HttpClient,
    private val userAgent: String
) : ReleaseSource {

    override fun fetchLatestRelease(url: String): ReleaseApiResponse {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github.v3+json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return ReleaseApiResponse(response.statusCode(), response.body())
    }

    override fun latestReleaseRedirect(url: String): String? {
        // Standard HttpURLConnection with redirect following disabled, to inspect the 302 Location.
        val conn = URI.create(url).toURL().openConnection() as java.net.HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.connect()
            return conn.getHeaderField("Location")
        } finally {
            conn.disconnect()
        }
    }
}

class HttpsUpdateFetcher(
    private val httpClient: HttpClient,
    private val userAgent: String
) : UpdateFetcher {

    override fun fetchBytes(url: String, maxBytes: Long): ByteArray {
        val response = open(url, Duration.ofSeconds(30))
        response.body().use { input ->
            val bytes = input.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (bytes.size > maxBytes) throw UpdateRejectedException(TOO_LARGE_MESSAGE)
            return bytes
        }
    }

    override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
        val response = open(url, Duration.ofMinutes(5))
        response.body().use { input: InputStream ->
            val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(0L)
            if (contentLength > maxBytes) throw UpdateRejectedException(TOO_LARGE_MESSAGE)
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                var written = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Download cancelled")
                    val read = input.read(buffer)
                    if (read == -1) break
                    written += read
                    if (written > maxBytes) throw UpdateRejectedException(TOO_LARGE_MESSAGE)
                    output.write(buffer, 0, read)
                    onProgress(written, contentLength)
                }
                output.flush()
            }
        }
    }

    private fun open(url: String, timeout: Duration): HttpResponse<InputStream> {
        val uri = URI.create(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) throw UpdateRejectedException(HTTPS_ONLY_MESSAGE)
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .header("User-Agent", userAgent)
            .timeout(timeout)
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        // HttpClient.Redirect.NORMAL never follows https -> http, but check where we ended up anyway.
        if (!response.uri().scheme.equals("https", ignoreCase = true)) {
            response.body().close()
            throw UpdateRejectedException(HTTPS_ONLY_MESSAGE)
        }
        if (response.statusCode() != 200) {
            response.body().close()
            throw UpdateHttpException(response.statusCode())
        }
        return response
    }

    companion object {
        const val HTTPS_ONLY_MESSAGE = "Updates are only downloaded over https."
        const val TOO_LARGE_MESSAGE = "The update file is larger than expected, so it wasn't downloaded."
    }
}
