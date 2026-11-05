package com.sphinx.peel.account

import android.content.Context
import com.sphinx.peel.engine.HiveStore
import java.io.File

/// A [HiveStore] in an owner-only file, wrapped in the Keystore (`KeyWall`),
/// per account: the Android counterpart of iOS's `Profile.hive` Keychain item.
/// The device credential and the peers' tokens never touch plain prefs.
class KeystoreHiveStore internal constructor(
    private val file: File,
    private val wall: KeyWall,
    private val aad: ByteArray,
) : HiveStore {
    constructor(context: Context, account: String = "default") : this(
        File(context.filesDir, "hive.$account.v1"),
        AndroidKeyWall("peel.hive.$account"),
        "peel.hive.$account".toByteArray(),
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
