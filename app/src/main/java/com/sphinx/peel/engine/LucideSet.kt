package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/// One stroked (and for a few dots, also filled) path of an icon, absolute segments in the 24×24 view box.
class LucidePath(val segs: List<Seg>, val filled: Boolean)

/// An icon: its paths, drawn with stroke 2, round caps and joins, no fill (lucide's own attributes).
class LucideShape(val paths: List<LucidePath>)

/// The whole of lucide as bundled (`assets/lucide/icons.json`, written by `tools/lucide-convert.py` from `lucide-static`):
/// every icon's SVG primitives, kept as read and turned into paths only for an icon asked for.
/// Format: `{"<name>": [prim…] | "@<name>"}`, prim = `["p",d]`, `["c",cx,cy,r,filled]`, `["r",x,y,w,h,rx,ry]`,
/// `["l",x1,y1,x2,y2]`, `["e",cx,cy,rx,ry]`, `["pl",points]`, `["pg",points]`; `"@<name>"` is an icon identical to that one.
class LucideSet private constructor(private val nodes: Map<String, Any>) {
    val names: Set<String> get() = nodes.keys

    /// [LucideNames.index] over [names], built once.
    val index: Map<String, String> by lazy { LucideNames.index(names) }

    /// The icon `name` is (resolved by [LucideNames]), or the grid's for none or an unknown one.
    fun shape(name: String?): LucideShape = exact(LucideNames.shape(name, index)) ?: exact(LucideNames.FALLBACK) ?: LucideShape(emptyList())

    /// The icon with exactly this lucide name, if there is one.
    fun exact(name: String): LucideShape? {
        var node = nodes[name] ?: return null
        if (node is String) node = nodes[node.removePrefix("@")] ?: return null
        @Suppress("UNCHECKED_CAST")
        return LucideShape((node as List<List<Any>>).map { LucidePrimitives.path(it) })
    }

    companion object {
        fun parse(json: String): LucideSet {
            val root = JSONObject(json)
            val m = LinkedHashMap<String, Any>(root.length() * 2)
            for (k in root.keys()) {
                val v = root.get(k)
                m[k] = if (v is JSONArray) List(v.length()) { i -> prim(v.getJSONArray(i)) } else v as String
            }
            return LucideSet(m)
        }

        fun parse(stream: InputStream): LucideSet = parse(stream.readBytes().toString(Charsets.UTF_8))

        private fun prim(a: JSONArray): List<Any> = List(a.length()) { i -> a.get(i).let { if (it is Number) it.toDouble() else it.toString() } }
    }
}

/// SVG primitives as lucide writes them, turned into [LucidePath]s through the one path parser.
object LucidePrimitives {
    /// A primitive's path data, as a string, for [SvgPath].
    fun data(p: List<Any>): String = when (p[0]) {
        "p" -> p[1] as String
        "c" -> ellipse(n(p, 1), n(p, 2), n(p, 3), n(p, 3))
        "e" -> ellipse(n(p, 1), n(p, 2), n(p, 3), n(p, 4))
        "l" -> "M${n(p, 1)} ${n(p, 2)}L${n(p, 3)} ${n(p, 4)}"
        "pl" -> "M" + (p[1] as String).trim()
        "pg" -> "M" + (p[1] as String).trim() + "Z"
        "r" -> rect(n(p, 1), n(p, 2), n(p, 3), n(p, 4), n(p, 5), n(p, 6))
        else -> ""
    }

    fun path(p: List<Any>): LucidePath = LucidePath(SvgPath.parse(data(p)), p[0] == "c" && n(p, 4) == 1.0)

    private fun n(p: List<Any>, i: Int): Double = (p[i] as Number).toDouble()

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double) =
        "M${cx - rx} ${cy}A$rx $ry 0 1 0 ${cx + rx} ${cy}A$rx $ry 0 1 0 ${cx - rx} ${cy}Z"

    private fun rect(x: Double, y: Double, w: Double, h: Double, rxIn: Double, ryIn: Double): String {
        val rx = minOf(rxIn, w / 2); val ry = minOf(ryIn, h / 2)
        if (rx <= 0 || ry <= 0) return "M$x ${y}H${x + w}V${y + h}H${x}Z"
        return "M${x + rx} ${y}H${x + w - rx}A$rx $ry 0 0 1 ${x + w} ${y + ry}V${y + h - ry}A$rx $ry 0 0 1 ${x + w - rx} ${y + h}" +
            "H${x + rx}A$rx $ry 0 0 1 $x ${y + h - ry}V${y + ry}A$rx $ry 0 0 1 ${x + rx} ${y}Z"
    }
}

/// A small least-recently-used map (what the icon cache needs; `android.util.LruCache` would tie the test to Robolectric).
class Lru<K : Any, V : Any>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized operator fun get(k: K): V? = map[k]
    @Synchronized operator fun set(k: K, v: V) { map[k] = v }
    val size: Int get() = synchronized(this) { map.size }
    @Synchronized fun keys(): List<K> = map.keys.toList()
}
