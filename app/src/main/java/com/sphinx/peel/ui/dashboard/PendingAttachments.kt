package com.sphinx.peel.ui.dashboard

import androidx.compose.ui.res.stringResource
import com.sphinx.peel.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.AgentAttachments
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.ModelSight
import com.sphinx.peel.engine.classifyOutgoingFile
import com.sphinx.peel.ui.components.prepareImageBytes
import com.sphinx.peel.ui.theme.Theme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// A file picked in the agent's DM and not yet sent. Its bytes live in a temp copy, [file], dropped when it is removed or sent.
class PendingFile(
    val id: String,
    val name: String,
    val mediaType: String,
    val file: File,
    val size: Long,
    val thumbnail: Bitmap?,
    val isPicture: Boolean,
)

/// The agent DM's pending attachments (peel's `FilesTray` model): what was picked, each picture's "let the model see it" switch ([ModelSight]),
/// and sending them in order. One per chat, held by `ConversationScreen`; [clear] on leaving drops every temp copy. Android's UI layer: the rules
/// are in `AgentAttachments`, `ModelSight` and `AttachmentMetadata` (pure, JVM-tested); this needs a device and is untested.
class PendingAttachments(private val context: Context) {
    val files = mutableStateListOf<PendingFile>()
    private val sight = ModelSight()
    private var sightTick by mutableIntStateOf(0)

    /// Whether the model sees picture [id] (read in composition: redraws when a switch changes).
    fun modelSees(id: String): Boolean {
        sightTick
        return sight.allows(id)
    }

    fun setModelSees(id: String, allowed: Boolean) {
        sight.set(id, allowed)
        sightTick++
    }

    /// Whether the "Peel needs to be open on your Mac" note shows (iOS `FilesTray`).
    val showsMacNote: Boolean
        get() {
            sightTick
            return AgentAttachments.showsMacNote(files.map { it.isPicture to sight.allows(it.id) })
        }

    fun remove(file: PendingFile) {
        files.remove(file)
        sight.drop(file.id)
        file.file.delete()
    }

    fun clear() {
        files.toList().forEach { remove(it) }
    }

    /// Adds a picture from the photo picker or camera, converted to a JPEG of at most 4096 px. Returns why it was refused, or null.
    suspend fun addPicture(uri: Uri): String? = add(uri, picked = true)

    /// Adds any file; a picture among them is converted to a JPEG like the picker's. Returns why it was refused, or null.
    suspend fun addFile(uri: Uri): String? = add(uri, picked = false)

    private suspend fun add(uri: Uri, picked: Boolean): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "file"
        val id = UUID.randomUUID().toString()
        val dir = File(context.cacheDir, "outgoing").also { it.mkdirs() }
        val temp = File(dir, "pending-$id")
        try {
            // Copied with a cap: a huge file is refused without being held whole.
            var copied = 0L
            val input = resolver.openInputStream(uri) ?: return@withContext context.getString(R.string.dash_attach_unreadable)
            input.use { source ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (!AgentAttachments.fits(copied)) {
                            temp.delete()
                            return@withContext AgentAttachments.tooLarge(displayName)
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
            val declared = if (picked) "image/jpg" else resolver.getType(uri)
            val (classifiedName, classifiedType) = classifyOutgoingFile(displayName, declared)
            var name = classifiedName
            var type = classifiedType
            var size = copied
            val isPicture = ModelSight.isPicture(type, name)
            if (isPicture) {
                val converted = runCatching {
                    prepareImageBytes(temp.readBytes(), AgentAttachments.PICTURE_MAX_DIMENSION, 85, flattenOnWhite = true)
                }.getOrNull()
                if (converted != null) {
                    temp.writeBytes(converted)
                    size = converted.size.toLong()
                    name = if (picked) "image.jpg" else AgentAttachments.jpegName(name)
                    type = "image/jpg"
                } else if (picked) {
                    temp.delete()
                    return@withContext context.getString(R.string.dash_attach_not_image)
                } // else: undecodable, sent as it is (peel does the same).
                if (!AgentAttachments.fits(size)) {
                    temp.delete()
                    return@withContext AgentAttachments.tooLarge(displayName)
                }
            }
            val thumbnail = if (isPicture) thumbnail(temp) else null
            withContext(Dispatchers.Main) { files.add(PendingFile(id, name, type, temp, size, thumbnail, isPicture)) }
            null
        } catch (e: Exception) {
            temp.delete()
            e.message ?: context.getString(R.string.dash_attach_unreadable)
        }
    }

    private fun thumbnail(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 400 || bounds.outHeight / sample > 400) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /// Sends every pending file in order. [caption] goes with the first only; if that file is refused, the text goes on its own. A refused file
    /// stays pending with its switch restored. Returns the refusals (the first is shown) and whether the caption went one way or the other.
    suspend fun sendAll(
        engine: Engine, chat: String, caption: String, replyUuid: String?, inThread: String?, onSent: suspend () -> Unit,
    ): SendOutcome {
        val errors = mutableListOf<String>()
        var captionSent = caption.isBlank()
        for ((index, file) in files.toList().withIndex()) {
            val allowed = sight.take(file.id)
            sightTick++
            try {
                val bytes = withContext(Dispatchers.IO) { file.file.readBytes() }
                engine.sendFileAttachment(
                    chat, bytes, file.name, file.mediaType, if (index == 0) caption else "", false,
                    replyUuid, inThread, modelMaySee = file.isPicture && allowed,
                )
                if (index == 0) captionSent = true
                files.remove(file)
                file.file.delete()
                onSent()
            } catch (e: Exception) {
                sight.restore(file.id, allowed)
                sightTick++
                errors += e.message ?: context.getString(R.string.dash_attach_send_failed, file.name)
            }
        }
        return SendOutcome(errors, captionSent)
    }

    class SendOutcome(val errors: List<String>, val captionSent: Boolean)
}

/// The pending files above the composer: a thumbnail or the name, an x, and under a picture the "let the model see it" switch; then the note.
@Composable
fun PendingTray(pending: PendingAttachments, modifier: Modifier = Modifier) {
    if (pending.files.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pending.files.forEach { file ->
                Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        Box(
                            Modifier.size(150.dp, 96.dp).clip(RoundedCornerShape(10.dp)).background(Theme.colors.fieldFill),
                            contentAlignment = Alignment.Center,
                        ) {
                            val thumbnail = file.thumbnail
                            if (thumbnail != null) {
                                Image(
                                    thumbnail.asImageBitmap(), contentDescription = file.name,
                                    contentScale = ContentScale.Crop, modifier = Modifier.size(150.dp, 96.dp),
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                                    Icon(Icons.Filled.Description, contentDescription = null, tint = Theme.colors.secondaryText)
                                    Text(
                                        file.name, style = MaterialTheme.typography.labelSmall, color = Theme.colors.text,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { pending.remove(file) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp).clip(CircleShape).background(Theme.colors.fieldFill),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dash_attach_remove, file.name), tint = Theme.colors.text, modifier = Modifier.size(14.dp))
                        }
                    }
                    if (file.isPicture) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ModelSight.TITLE, style = MaterialTheme.typography.labelSmall, color = Theme.colors.text, modifier = Modifier.weight(1f))
                            Switch(checked = pending.modelSees(file.id), onCheckedChange = { pending.setModelSees(file.id, it) })
                        }
                        Text(ModelSight.HELP, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
                    }
                }
            }
        }
        if (pending.showsMacNote) {
            Text(AgentAttachments.MAC_NOTE, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
        }
    }
}
