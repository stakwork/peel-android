package com.sphinx.peel.ui.apps

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Lru
import com.sphinx.peel.engine.LucideNames
import com.sphinx.peel.engine.LucideSet
import com.sphinx.peel.engine.LucideShape
import com.sphinx.peel.engine.Seg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// An app's icon (iOS `WorkspaceAppIcon`): the manifest's lucide name drawn as that lucide icon, any of them, the grid
/// for none or one lucide has not. The icons are `assets/lucide/icons.json`, parsed once off the main thread
/// (`LucideSet`, name rules in `LucideNames`) and each drawn as an `ImageVector` (24×24, stroke 2, round caps and joins,
/// tinted by the surrounding tint) kept in a small LRU.
object AppIcons {
    private const val ASSET = "lucide/icons.json"
    private val cache = Lru<String, ImageVector>(256)

    @Volatile private var set: LucideSet? = null

    /// The parsed set, read from the assets on first use (call off the main thread).
    fun load(context: Context): LucideSet = set ?: synchronized(this) {
        set ?: context.applicationContext.assets.open(ASSET).use { LucideSet.parse(it) }.also { set = it }
    }

    /// The vector for a manifest's name, once the set is loaded; null before (draw nothing yet).
    fun vector(name: String?): ImageVector? {
        val s = set ?: return null
        val key = LucideNames.shape(name, s.index)
        cache[key]?.let { return it }
        return build(key, s.shape(name)).also { cache[key] = it }
    }

    internal fun build(name: String, shape: LucideShape): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in shape.paths) {
            b.path(
                fill = if (p.filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) { for (s in p.segs) add(s) }
        }
        return b.build()
    }

    private fun PathBuilder.add(s: Seg) {
        when (s) {
            is Seg.Move -> moveTo(s.x, s.y)
            is Seg.Line -> lineTo(s.x, s.y)
            is Seg.Cubic -> curveTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
            is Seg.Quad -> quadTo(s.x1, s.y1, s.x, s.y)
            Seg.Close -> close()
        }
    }
}

@Composable
fun WorkspaceAppIcon(name: String?, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(AppIcons.vector(name) != null) }
    LaunchedEffect(Unit) { if (!ready) { withContext(Dispatchers.IO) { AppIcons.load(context) }; ready = true } }
    val v = if (ready) AppIcons.vector(name) else null
    if (v != null) Icon(v, null, modifier.size(size), tint = tint) else androidx.compose.foundation.layout.Spacer(modifier.size(size))
}
