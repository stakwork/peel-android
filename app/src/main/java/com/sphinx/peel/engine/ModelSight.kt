package com.sphinx.peel.engine

/// Which pictures the person lets the model see, in the agent's DM (peel's `PeelUI/ModelSight.swift`). Off: only the text the Mac read from the
/// picture goes to the model. On (the default): the picture goes to the model provider too. The state is the set of files turned OFF, by an id the
/// caller picks (a pending file's id), so a file never mentioned is on. Only pictures offer the switch. Pure; JVM-tested.
class ModelSight {
    private val off = mutableSetOf<String>()

    /// Whether a file gets the switch at all: a picture, by its media type, else by its name's extension.
    fun offers(mediaType: String?, name: String): Boolean = isPicture(mediaType, name)

    /// Whether the model may see [id] (on unless turned off).
    fun allows(id: String): Boolean = id !in off

    /// Turns the picture [id] on or off.
    fun set(id: String, allowed: Boolean) {
        if (allowed) off.remove(id) else off.add(id)
    }

    /// Forgets [id]'s state (the file was removed).
    fun drop(id: String) {
        off.remove(id)
    }

    /// Reads [id]'s state and forgets it (the file is being sent).
    fun take(id: String): Boolean = allows(id).also { drop(id) }

    /// Puts back what [take] read, for a file that was refused and stays pending.
    fun restore(id: String, allowed: Boolean) = set(id, allowed)

    /// The ids turned off, for a view that redraws on a change.
    val turnedOff: Set<String> get() = off.toSet()

    companion object {
        const val TITLE = "Let the model see this picture"
        const val HELP = "Off: only the text read from it goes to the model. On: the picture is sent to your model provider."

        private val PICTURE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "tif", "tiff", "bmp")

        fun isPicture(mediaType: String?, name: String): Boolean {
            val type = mediaType?.lowercase()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
            if (type != null) return type.startsWith("image/")
            return name.substringAfterLast('.', "").lowercase() in PICTURE_EXTENSIONS
        }
    }
}
