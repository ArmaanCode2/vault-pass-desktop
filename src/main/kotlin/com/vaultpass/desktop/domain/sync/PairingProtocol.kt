package com.vaultpass.desktop.domain.sync

/**
 * Defines an out-of-band trust handshake mechanism.
 * Before two devices (e.g. Desktop and Android) can synchronize over an untrusted
 * medium like LAN or Bluetooth, they must establish trust. 
 * A future implementation (like QrPairingProtocolImpl) will fulfill this contract.
 */
interface PairingProtocol {
    
    /**
     * Initiates the pairing process as the host.
     * For a QR protocol, this might return a string representing the QR code data.
     */
    suspend fun generatePairingRequest(): String
    
    /**
     * Accepts a pairing request from a client.
     * 
     * @param responseData The data received from the client (e.g. a scanned QR response).
     * @return True if the cryptographic handshake succeeded and trust is established.
     */
    suspend fun verifyPairingResponse(responseData: String): Boolean
}
