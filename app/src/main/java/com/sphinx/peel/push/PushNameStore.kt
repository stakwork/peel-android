package com.sphinx.peel.push

import android.content.Context
import com.sphinx.peel.account.AndroidKeyWall
import com.sphinx.peel.account.FileSecretStore
import com.sphinx.peel.account.KeyWall
import java.io.File

/// The name directory ([PushNameDirectory]) sealed with a Keystore AES-GCM
/// key (`KeyWall`, the same non-authenticated key kind the PIN blob uses),
/// in a private file. Reading needs neither the PIN nor the core state, so
/// `PeelMessagingService` can name a push while the app is locked and no
/// engine exists. iOS keeps its map in a Keychain-sealed file for the
/// extension; this is the same idea.
class PushNameStore(private val file: File, private val wall: KeyWall) {
    /// Null when nothing is stored or it cannot be opened (never throws).
    fun read(): PushNameDirectory? = try {
        if (!file.exists()) null
        else PushNameDirectory.decode(String(wall.unwrap(file.readBytes(), AAD), Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    /// Returns whether it was written.
    fun write(directory: PushNameDirectory): Boolean = try {
        file.parentFile?.mkdirs()
        FileSecretStore.writeOwnerOnly(file, wall.wrap(directory.cleaned().encoded().toByteArray(Charsets.UTF_8), AAD))
        true
    } catch (e: Exception) {
        false
    }

    /// Account removed: the map and its key go.
    fun clear() {
        file.delete()
        runCatching { wall.delete() }
    }

    companion object {
        private val AAD = "peel.push.names.v1".toByteArray()

        fun forContext(context: Context): PushNameStore {
            val app = context.applicationContext
            return PushNameStore(File(app.filesDir, "push-names.v1"), AndroidKeyWall("peel.push.names"))
        }
    }
}
