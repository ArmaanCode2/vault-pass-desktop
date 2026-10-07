package com.vaultpass.desktop.data.database

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Closes the connection and waits until it is physically closed. closeConnection() itself
 * returns at once and closes in the background, so a test that then reads, hashes or deletes the
 * database files waits with this first. Fails the test when the close doesn't finish within 5 s
 * (something is still holding the connection), instead of carrying on with the file still open.
 */
internal fun SQLiteConnectionManager.closeAndWait() {
    val closed = runBlocking { withTimeoutOrNull(5_000) { closeConnectionAndWait(); true } }
    check(closed == true) { "The database connection wasn't closed within 5 s: something still holds it" }
}
