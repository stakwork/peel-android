package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject

// A box's linked places (strut's plans/jamie-in-tribes.md §4 and §5.4, specs/API.md §13.4 and §13.6 "Places";
// docs/phases/54-jamie-from-a-box.md): where a person asks one of the box's hosts to answer, as them. To Peel, a tribe its owner
// linked for the box's Sphinx daemon (the box's Jamie) to join and answer `@jamie` in. Strut keeps the list and the daemon follows
// it; Peel reads and changes the person's own with the token it holds for the box ([BoxLink.token]). Pure (the HTTP is behind
// [BoxHttp]), JVM-tested (`BoxPlacesTest`).

/// A place as strut answers one (`Place`, API.md §13.4).
data class BoxPlace(
    /// The host's name: "sphinx".
    val host: String,
    /// The place on its medium: a tribe's pubkey, as it was linked.
    val key: String,
    /// How the host joins it: the tribe's link.
    val link: String,
    /// What the person saw it called.
    val name: String?,
    /// The linker, `sphinx:<66 hex>`.
    val account: String,
    /// When it was linked, unix milliseconds.
    val at: Long,
) {
    /// Whether this is [tribe]'s place for the box's Sphinx host.
    fun isTribe(tribe: String): Boolean = host == BoxPlaces.SPHINX && key.equals(tribe, ignoreCase = true)

    companion object {
        /// Null for anything that is not a place.
        internal fun parse(json: JSONObject?): BoxPlace? {
            json ?: return null
            val host = (json.opt("host") as? String)?.ifEmpty { null } ?: return null
            val key = (json.opt("key") as? String)?.ifEmpty { null } ?: return null
            val link = (json.opt("link") as? String)?.ifEmpty { null } ?: return null
            val account = (json.opt("account") as? String)?.ifEmpty { null } ?: return null
            return BoxPlace(host, key, link, (json.opt("name") as? String)?.ifEmpty { null }, account, WalletProtocol.integer(json.opt("at")) ?: 0)
        }
    }
}

/// What `GET /people/places` answers: the host ready to take a place from the person's account, and the places they linked.
data class BoxPlaces(val hosts: List<Host>, val places: List<BoxPlace>) {
    /// A host ready here: its name and its own account on its medium.
    data class Host(val host: String, val account: String)

    /// The box's Jamie in tribes: its Sphinx host's own key (66 hex, lower case), what Peel approves its join request by. Null while
    /// the box has no Sphinx host ready (none, or it hasn't said who it is).
    val jamie: String? get() = hosts.firstNotNullOfOrNull { if (it.host == SPHINX) sphinxKey(it.account) else null }

    /// [tribe]'s place for the Sphinx host, when the person linked it here.
    fun place(tribe: String): BoxPlace? = places.firstOrNull { it.isTribe(tribe) }

    /// With [place] linked, in place of the same tribe's, and [account] as the Sphinx host's (a link's answer).
    fun linking(place: BoxPlace, account: String): BoxPlaces = BoxPlaces(
        hosts = hosts.filter { it.host != place.host } + Host(place.host, account),
        places = listOf(place) + places.filter { !(it.host == place.host && it.key.equals(place.key, ignoreCase = true)) },
    )

    /// Without [tribe]'s place.
    fun unlinking(tribe: String): BoxPlaces = copy(places = places.filter { !it.isTribe(tribe) })

    companion object {
        /// The Sphinx host's name, and its kind of account.
        const val SPHINX = "sphinx"

        /// A strut from before places: no host ready, nothing linked.
        val NONE = BoxPlaces(emptyList(), emptyList())

        /// The key a Sphinx account names (`sphinx:<66 hex>`, or the bare key), lower case; null for anything else.
        fun sphinxKey(account: String): String? {
            val key = account.removePrefix("$SPHINX:").lowercase()
            return key.takeIf { isKey(it) }
        }

        /// A compressed secp256k1 key: 66 hex, `02` or `03` first.
        fun isKey(text: String): Boolean =
            text.length == 66 && (text.startsWith("02") || text.startsWith("03")) && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /// `{ hosts: [{ host, account }], places: Place[] }`; null when it is not that. A host or place that can't be read is
        /// skipped.
        fun parse(body: String): BoxPlaces? {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val hosts = json.opt("hosts") as? JSONArray ?: return null
            val places = json.opt("places") as? JSONArray ?: return null
            return BoxPlaces(
                (0 until hosts.length()).mapNotNull { i ->
                    val h = hosts.optJSONObject(i) ?: return@mapNotNull null
                    val name = (h.opt("host") as? String)?.ifEmpty { null } ?: return@mapNotNull null
                    val account = (h.opt("account") as? String)?.ifEmpty { null } ?: return@mapNotNull null
                    Host(name, account)
                },
                (0 until places.length()).mapNotNull { BoxPlace.parse(places.optJSONObject(it)) },
            )
        }
    }
}

/// A tribe as `POST /people/places` links it for the box's Sphinx host: its pubkey, its link and its name.
data class TribePlace(val key: String, val link: String, val name: String?)

/// What a link answers: the place, and the Sphinx host's key (the box's Jamie), which Peel approves its join request by.
data class PlaceLinked(val place: BoxPlace, val jamie: String)

/// The box refused the token (`401`): it ran out, or the member removed the link. [BoxesModel.asMember] renews it once.
class BoxTokenRefused(name: String) : BoxException("$name didn't take Peel's link. Link Peel again from Connect there.")

/// strut's place routes for a person (API.md §13.6, "Places"), on one box with the token Peel holds there.
class PlacesClient(private val box: BoxLink, private val http: BoxHttp) {
    /// The box as its messages name it.
    private val name: String get() = box.shortName

    /// The person's places and the host ready to take one (`GET /people/places`). A strut from before places (`404`) has neither:
    /// Jamie isn't offered from it.
    suspend fun read(): BoxPlaces {
        val r = http.call("GET", "${box.url}/people/places", null, box.token, LIMIT, 20)
        if (r.status == 404) return BoxPlaces.NONE
        refused(r, "read its tribes")?.let { throw it }
        return BoxPlaces.parse(r.body) ?: throw BoxException("$name's answer about Jamie could not be read.")
    }

    /// Links [tribe] for the box's Sphinx host (`POST /people/places`): its Jamie joins it and answers `@jamie` there, as this
    /// person. The person's own place again is refreshed. Throws another person's place (`409 place_linked:`) and a box with no
    /// Sphinx host ready (`404 no_host:`) in words.
    suspend fun link(tribe: TribePlace): PlaceLinked {
        val body = JSONObject().put("host", BoxPlaces.SPHINX).put("key", tribe.key).put("link", tribe.link)
        // One line of at most 80 characters, as strut keeps a name.
        tribe.name?.let { TextLine.clean(it, 80).trim() }?.takeIf { it.isNotEmpty() }?.let { body.put("name", it) }
        val r = http.call("POST", "${box.url}/people/places", body.toString(), box.token, LIMIT, 30)
        when {
            r.status == 409 && code(r.body) == "place_linked" ->
                throw BoxException("Someone else on $name already added Jamie to this tribe.")
            r.status == 404 && code(r.body) == "no_host" -> throw BoxException("$name isn't ready for tribes yet.")
        }
        refused(r, "add Jamie")?.let { throw it }
        val json = runCatching { JSONObject(r.body) }.getOrNull()
        val place = BoxPlace.parse(json?.optJSONObject("place"))?.takeIf { it.isTribe(tribe.key) }
        val jamie = (json?.opt("account") as? String)?.let(BoxPlaces::sphinxKey)
        if (place == null || jamie == null) throw BoxException("$name's answer about Jamie could not be read.")
        return PlaceLinked(place, jamie)
    }

    /// Takes the person's place [key] away (`DELETE /people/places/sphinx/<key>`): the box's Jamie leaves the tribe. False when it
    /// was not theirs here (already removed, or the daemon removed it).
    suspend fun unlink(key: String): Boolean {
        val r = http.call("DELETE", "${box.url}/people/places/${BoxPlaces.SPHINX}/${segment(key)}", null, box.token, LIMIT, 30)
        refused(r, "remove Jamie")?.let { throw it }
        return runCatching { JSONObject(r.body).optBoolean("removed") }.getOrDefault(false)
    }

    /// Null for a 200; else strut's refusal in words, the token's own ([BoxTokenRefused]) for a 401.
    private fun refused(r: BoxResponse, couldNot: String): BoxException? = when (r.status) {
        200 -> null
        401 -> BoxTokenRefused(name)
        429 -> BoxException("$name is busy. Try again in a minute.")
        else -> BoxException("$name could not $couldNot: ${said(r.body) ?: "HTTP ${r.status}"}.")
    }

    companion object {
        /// The most a places answer may be: a person's places, each a few hundred bytes.
        const val LIMIT = 256 * 1024

        private fun said(body: String): String? =
            (runCatching { JSONObject(body) }.getOrNull()?.opt("error") as? String)?.let { TextLine.clean(it, 200) }?.ifEmpty { null }

        /// strut's error code: what comes before the colon (`place_linked: …`).
        internal fun code(body: String): String? = said(body)?.substringBefore(':', "")?.ifEmpty { null }

        /// [text] as one path segment: everything but the unreserved characters percent-encoded.
        internal fun segment(text: String): String = text.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val c = (b.toInt() and 0xff).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") "$c" else "%%%02X".format(b.toInt() and 0xff)
        }
    }
}
