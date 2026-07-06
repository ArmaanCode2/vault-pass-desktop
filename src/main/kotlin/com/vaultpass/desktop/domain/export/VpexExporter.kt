package com.vaultpass.desktop.domain.export

/**
 * Defines the contract for exporting a vault to the shared VPEX format.
 */
interface VpexExporter {
    /**
     * Packages the current vault into a VpexContainer and writes it to the specified destination.
     *
     * @param destinationPath The local file path to save the .vpex file.
     * @return True if the export was successful.
     */
    suspend fun exportVault(destinationPath: String): Boolean
}
