package com.vaultpass.desktop.data.backup

import com.vaultpass.desktop.domain.backup.BackupSerializer
import com.vaultpass.desktop.domain.backup.models.BackupFormat
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.backup.errors.BackupException
import com.vaultpass.desktop.domain.backup.models.format.BackupPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

class BackupSerializerImpl : BackupSerializer {
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun serialize(payload: BackupPayload, format: BackupFormat): BackupResult<ByteArray> {
        return try {
            if (format != BackupFormat.JSON_V1) {
                return BackupResult.Failure(BackupException.InvalidFormat("Only JSON_V1 is supported currently."))
            }
            
            val jsonString = json.encodeToString(payload)
            val bytes = jsonString.toByteArray(Charsets.UTF_8)
            
            // Compress with GZIP
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { gzipOS ->
                gzipOS.write(bytes)
            }
            
            BackupResult.Success(baos.toByteArray())
        } catch (e: Exception) {
            BackupResult.Failure(BackupException.CorruptedData("Serialization or compression failed: ${e.message}"))
        }
    }

    override fun deserialize(data: ByteArray, format: BackupFormat): BackupResult<BackupPayload> {
        return try {
            if (format != BackupFormat.JSON_V1) {
                return BackupResult.Failure(BackupException.InvalidFormat("Only JSON_V1 is supported currently."))
            }
            
            // Decompress with GZIP
            val bais = ByteArrayInputStream(data)
            val jsonString = GZIPInputStream(bais).use { gzipIS ->
                gzipIS.readBytes().toString(Charsets.UTF_8)
            }
            
            val result = json.decodeFromString<BackupPayload>(jsonString)
            BackupResult.Success(result)
        } catch (e: Exception) {
            BackupResult.Failure(BackupException.CorruptedData("Deserialization or decompression failed: ${e.message}"))
        }
    }
}
