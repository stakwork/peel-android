package com.sphinx.peel.engine

import org.json.JSONObject

/// The `metadata` of an attachment sent to the agent's DM (peel's `MessageMetadata.vision`): `{"peel":{"v":1,"vision":true}}` when a picture may be
/// shown to the model, merged into the time-zone metadata (`{"tz":"GMT+1"}`) as one JSON object so both keys survive. Pure; JVM-tested.
object AttachmentMetadata {
    /// The exact metadata of a picture the model may see.
    const val VISION = """{"peel":{"v":1,"vision":true}}"""

    /// Whether this send carries `vision`: only in the agent's own DM, only a picture, only when the person allowed it.
    fun vision(isAgentChat: Boolean, mediaType: String, modelMaySee: Boolean): Boolean =
        isAgentChat && modelMaySee && mediaType.lowercase().startsWith("image/")

    /// The `metadata` string of a send: [timeZone] (the existing metadata, or null) with `peel.vision` when [vision]; null when there is
    /// nothing to say or the result is longer than [MessageMetadata.MAX_LENGTH] bytes (dropped as peel drops it).
    fun merged(timeZone: String?, vision: Boolean): String? {
        if (!vision) return timeZone
        val base = timeZone?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val peel = base.optJSONObject("peel") ?: JSONObject()
        peel.put("v", 1)
        peel.put("vision", true)
        base.put("peel", peel)
        val text = base.toString()
        return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= MessageMetadata.MAX_LENGTH }
    }
}
