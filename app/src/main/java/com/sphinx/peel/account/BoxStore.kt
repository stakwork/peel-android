package com.sphinx.peel.account

import android.content.Context
import com.sphinx.peel.engine.BoxStore
import java.io.File

/// A [BoxStore] in an owner-only file, wrapped in the Keystore (`KeyWall`) under a key of its own, per account: the Android
/// counterpart of iOS's `Profile.boxes` Keychain item (`dev.peel.boxes`, docs/phases/45-box-link.md). Beside `KeystoreHiveStore`,
/// never in it: its own file (`boxes.<account>.v1`) and its own key, so Hive's pairing is never read, moved or written here.
/// The members' tokens never touch plain prefs.
class KeystoreBoxStore internal constructor(
    private val file: File,
    private val wall: KeyWall,
    private val aad: ByteArray,
) : BoxStore {
    constructor(context: Context, account: String = "default") : this(
        File(context.filesDir, "boxes.$account.v1"),
        AndroidKeyWall("peel.boxes.$account"),
        "peel.boxes.$account".toByteArray(),
    )

    @Synchronized
    override fun read(): String? {
        if (!file.exists()) return null
        return String(wall.unwrap(file.readBytes(), aad), Charsets.UTF_8)
    }

    @Synchronized
    override fun write(text: String) {
        FileSecretStore.writeOwnerOnly(file, wall.wrap(text.toByteArray(Charsets.UTF_8), aad))
    }

    @Synchronized
    override fun delete() {
        file.delete()
        runCatching { wall.delete() }
    }
}
