package com.vaultpass.desktop.domain.export

/**
 * Defines the contract for importing a shared VPEX format file.
 */
interface VpexImporter {
    /**
     * Reads only the cleartext VpexManifest from the file.
     * This allows the application to validate versions and compatibility
     * (e.g., via MigrationRunner) before prompting for passwords or attempting decryption.
     *
     * @param sourcePath The local file path to the .vpex file.
     * @return The cleartext manifest.
     * @throws Exception if the file is malformed or unreadable.
     */
    suspend fun analyzeImport(sourcePath: String): VpexManifest

    /**
     * Performs the actual decryption and merging of the imported vault.
     *
     * @param sourcePath The local file path to the .vpex file.
     * @return True if the import was successful.
     */
    suspend fun executeImport(sourcePath: String): Boolean
}
