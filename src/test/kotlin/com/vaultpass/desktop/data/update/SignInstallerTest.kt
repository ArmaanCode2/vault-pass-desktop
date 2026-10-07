package com.vaultpass.desktop.data.update

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Runs tools/SignInstaller.java end to end, as `java tools/SignInstaller.java`, against a throwaway
 * keystore and a small fake project (repo key file, app image jars, installer) in a temp directory.
 * The fake app image carries the app's real UpdateSignatureVerifier classes and the Kotlin stdlib, so the
 * signer's own verification runs the same code an installed app runs.
 */
class SignInstallerTest {

    @TempDir
    lateinit var tempDir: File

    private val password = "throwaway-only"
    private val version = "2.0.0"
    private val installerName = "VaultPass-Setup-v$version.exe"
    private val installerBytes = ByteArray(300_000) { (it * 7 % 249).toByte() }

    private val javaHome = File(System.getProperty("java.home"))
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val signer = File("tools/SignInstaller.java").absoluteFile

    private data class Result(val exitCode: Int, val out: String, val err: String)

    private fun tool(name: String) = File(javaHome, "bin/" + if (isWindows) "$name.exe" else name)

    private fun keystore(name: String, curve: String = "secp256r1"): File {
        val file = File(tempDir, name)
        val process = ProcessBuilder(
            tool("keytool").path, "-genkeypair", "-keystore", file.path, "-storetype", "PKCS12",
            "-storepass", password, "-alias", "update", "-keyalg", "EC", "-groupname", curve,
            "-dname", "CN=VaultPass throwaway test key", "-validity", "2", "-noprompt"
        ).redirectErrorStream(true).redirectOutput(File(tempDir, "$name.keytool.log")).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), File(tempDir, "$name.keytool.log").readText())
        return file
    }

    private fun spki(keystore: File): ByteArray {
        val store = KeyStore.getInstance("PKCS12")
        keystore.inputStream().use { store.load(it, password.toCharArray()) }
        return store.getCertificate("update").publicKey.encoded
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun newProject(name: String): File = File(tempDir, name).apply {
        File(this, "dist").mkdirs()
        File(this, "dist/$installerName").writeBytes(installerBytes)
    }

    private fun pemFile(project: File) = File(project, "src/main/resources/update-signing-key.pem")

    /** Writes the app image: one app jar (with [pem] and [imageVersion] when given) plus the Kotlin stdlib. */
    private fun buildAppImage(project: File, pem: ByteArray?, imageVersion: String = version) {
        val appDir = File(project, "build/compose/binaries/main/app/VaultPass/app")
        appDir.deleteRecursively()
        appDir.mkdirs()
        val classesDir = File(UpdateSignatureVerifier::class.java.protectionDomain.codeSource.location.toURI())
        val packageDir = File(classesDir, "com/vaultpass/desktop/data/update")
        JarOutputStream(File(appDir, "vaultpass-desktop.jar").outputStream()).use { jar ->
            fun put(name: String, bytes: ByteArray) {
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
            packageDir.listFiles()!!.filter { it.name.startsWith("UpdateSignatureVerifier") }.forEach {
                put("com/vaultpass/desktop/data/update/${it.name}", it.readBytes())
            }
            put("version.txt", "$imageVersion\n".toByteArray())
            if (pem != null) put(UpdateSignatureVerifier.PUBLIC_KEY_RESOURCE, pem)
        }
        val stdlib = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())
        stdlib.copyTo(File(appDir, stdlib.name))
    }

    private fun runSigner(
        project: File,
        keystore: File,
        installerVersion: String = version,
        installer: File = File(project, "dist/$installerName"),
        stdinPassword: String? = password,
        vararg extra: String
    ): Result {
        val args = mutableListOf(
            tool("java").path, signer.path,
            "--keystore", keystore.path, "--installer", installer.path, "--version", installerVersion,
            "--project-dir", project.path
        )
        args += extra
        if (stdinPassword != null) args += "--password-stdin"
        val out = File.createTempFile("signer", ".out", tempDir)
        val err = File.createTempFile("signer", ".err", tempDir)
        val process = ProcessBuilder(args).redirectOutput(out).redirectError(err).start()
        process.outputStream.use { stdin -> if (stdinPassword != null) stdin.write("$stdinPassword\n".toByteArray()) }
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "The signer did not finish")
        return Result(process.exitValue(), out.readText(), err.readText())
    }

    private fun signatureOf(project: File) = File(project, "dist/$installerName.sig")

    @Test
    fun signerWritesTheKeyThenSignsAnInstallerTheAppAccepts() {
        assertTrue(signer.isFile, "tools/SignInstaller.java not found from ${File(".").absolutePath}")
        val keystore = keystore("release-throwaway.p12")
        val project = newProject("project")
        val installer = File(project, "dist/$installerName")

        // 1. No key file in the repo yet: the signer writes it, prints the SPKI SHA-256 and signs nothing.
        val first = runSigner(project, keystore)
        assertEquals(2, first.exitCode, first.err)
        val spkiSha256 = hex(MessageDigest.getInstance("SHA-256").digest(spki(keystore)))
        assertTrue(first.out.contains(spkiSha256), "The SubjectPublicKeyInfo SHA-256 is printed:\n${first.out}")
        val pem = pemFile(project).readBytes()
        assertArrayEquals(spki(keystore), UpdateSignatureVerifier.parsePublicKeyPem(String(pem, Charsets.US_ASCII)).encoded)
        assertFalse(signatureOf(project).exists())

        // 2. The app image was built before the key existed: refused, nothing signed.
        buildAppImage(project, pem = null)
        val keyless = runSigner(project, keystore)
        assertEquals(1, keyless.exitCode)
        assertTrue(keyless.err.contains("has no update-signing-key.pem"), keyless.err)
        assertFalse(signatureOf(project).exists())

        // 3. Rebuilt with the key inside: signed, and the app's verifier accepts it only under this version's name.
        buildAppImage(project, pem = pem)
        val signed = runSigner(project, keystore)
        assertEquals(0, signed.exitCode, signed.err)
        assertTrue(signed.out.contains(spkiSha256))
        val publicKey = UpdateSignatureVerifier.parsePublicKeyPem(String(pem, Charsets.US_ASCII))
        val signature = signatureOf(project).readText()
        assertTrue(UpdateSignatureVerifier.verifyFile(publicKey, installer, installerName, signature))
        for (otherName in listOf("VaultPass-Setup-v2.0.1.exe", "VaultPass-Setup-v1.0.0.exe", "VaultPass-Setup-v2.0.0-beta.exe")) {
            assertFalse(UpdateSignatureVerifier.verifyFile(publicKey, installer, otherName, signature), "Accepted as $otherName")
        }
        val installerSha256 = UpdateSignatureVerifier.sha256Hex(installer)
        assertEquals("$installerSha256  $installerName\n", File(project, "dist/$installerName.sha256").readText())

        // 4. An existing .sig is kept unless --force is passed.
        val again = runSigner(project, keystore)
        assertEquals(1, again.exitCode)
        assertTrue(again.err.contains("already exists"), again.err)
        assertEquals(signature, signatureOf(project).readText())
        val forced = runSigner(project, keystore, extra = arrayOf("--force"))
        assertEquals(0, forced.exitCode, forced.err)
        assertTrue(UpdateSignatureVerifier.verifyFile(publicKey, installer, installerName, signatureOf(project).readText()))
    }

    @Test
    fun signerRefusesWrongVersionsNamesKeysAndImages() {
        val keystore = keystore("release-throwaway.p12")
        val project = newProject("project")
        val pem = ("-----BEGIN PUBLIC KEY-----\n" +
            java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(spki(keystore)) +
            "\n-----END PUBLIC KEY-----\n").toByteArray()
        pemFile(project).parentFile.mkdirs()
        pemFile(project).writeBytes(pem)
        buildAppImage(project, pem = pem)

        fun assertRefused(result: Result, reason: String) {
            assertEquals(1, result.exitCode, "Expected a refusal mentioning \"$reason\"; out:\n${result.out}\nerr:\n${result.err}")
            assertTrue(result.err.contains(reason), result.err)
            assertFalse(signatureOf(project).exists())
        }

        // Pre-release and odd versions, before any password is read.
        for (bad in listOf("2.0.0-beta", "2.0", "2.0.0.1", "v2.0.0")) {
            val named = File(project, "dist/VaultPass-Setup-v$bad.exe").apply { writeBytes(installerBytes) }
            assertRefused(runSigner(project, keystore, installerVersion = bad, installer = named, stdinPassword = null), "major.minor.patch")
            named.delete()
        }
        // The installer name must be exactly VaultPass-Setup-v<version>.exe.
        assertRefused(runSigner(project, keystore, installerVersion = "2.0.1"), "must be named VaultPass-Setup-v2.0.1.exe")
        val renamed = File(project, "dist/VaultPass-Installer.exe").apply { writeBytes(installerBytes) }
        assertRefused(runSigner(project, keystore, installer = renamed), "must be named")
        // No console and no --password-stdin.
        assertRefused(runSigner(project, keystore, stdinPassword = null), "No interactive console")
        // Wrong password.
        assertEquals(1, runSigner(project, keystore, stdinPassword = "wrong").exitCode)
        assertFalse(signatureOf(project).exists())
        // A P-384 key is not P-256.
        assertRefused(runSigner(project, keystore("p384-throwaway.p12", curve = "secp384r1")), "must be EC P-256")
        // Another P-256 key than the one in the repo.
        assertRefused(runSigner(project, keystore("other-throwaway.p12")), "is not the update key")
        // The app image holds a different key, or is another version.
        buildAppImage(project, pem = pem.copyOf().also { it[40] = (it[40] + 1).toByte() })
        assertRefused(runSigner(project, keystore), "different update-signing-key.pem")
        buildAppImage(project, pem = pem, imageVersion = "1.9.0")
        assertRefused(runSigner(project, keystore), "The app image is version 1.9.0")
    }
}
