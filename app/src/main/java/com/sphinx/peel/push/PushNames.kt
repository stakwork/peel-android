package com.sphinx.peel.push

import org.json.JSONObject
import uniffi.sphinxrs.decryptChildIndex

/// Names a push's chat the way iOS' notification extension does
/// (`PushNameDirectory` in `PeelPush`): a push carries only the child index
/// of its contact or tribe sealed under the push key; the app keeps a map
/// index -> name, and the notification reads "You have new messages from
/// NAME" (a contact) or "... in TRIBE". Any failure is the generic line.
///
/// The JSON is one flat object with iOS' key names, `contact-<idx>` and
/// `tribe-<idx>`. Android's own addition in the same object, for the tap:
/// `chat-contact-<idx>` / `chat-tribe-<idx>` -> the chat id the app opens
/// (the contact's pubkey, the tribe's chat id), so a tap needs no core state.
data class PushNameDirectory(
    val contacts: Map<ULong, String> = emptyMap(),
    val tribes: Map<ULong, String> = emptyMap(),
    val contactChats: Map<ULong, String> = emptyMap(),
    val tribeChats: Map<ULong, String> = emptyMap(),
) {
    /// The same directory with every name cleaned (see [cleanName]) and
    /// empty ones dropped.
    fun cleaned(): PushNameDirectory = PushNameDirectory(
        contacts = contacts.cleanedNames(),
        tribes = tribes.cleanedNames(),
        contactChats = contactChats.filterValues { it.isNotBlank() },
        tribeChats = tribeChats.filterValues { it.isNotBlank() },
    )

    fun encoded(): String {
        val json = JSONObject()
        for ((idx, name) in contacts) json.put("contact-$idx", name)
        for ((idx, name) in tribes) json.put("tribe-$idx", name)
        for ((idx, chat) in contactChats) json.put("chat-contact-$idx", chat)
        for ((idx, chat) in tribeChats) json.put("chat-tribe-$idx", chat)
        return json.toString()
    }

    val isEmpty: Boolean get() = contacts.isEmpty() && tribes.isEmpty() && contactChats.isEmpty() && tribeChats.isEmpty()

    companion object {
        const val MAX_NAME_LENGTH = 100

        /// Control characters out, trimmed, at most [MAX_NAME_LENGTH]; null when nothing is left.
        fun cleanName(raw: String): String? {
            val cleaned = raw.filterNot { it.isISOControl() }.trim().take(MAX_NAME_LENGTH).trim()
            return cleaned.takeIf { it.isNotEmpty() }
        }

        /// Unreadable or malformed text is an empty directory; unknown keys are ignored.
        fun decode(text: String): PushNameDirectory {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return PushNameDirectory()
            val contacts = mutableMapOf<ULong, String>()
            val tribes = mutableMapOf<ULong, String>()
            val contactChats = mutableMapOf<ULong, String>()
            val tribeChats = mutableMapOf<ULong, String>()
            for (key in json.keys()) {
                val value = json.optString(key, "")
                when {
                    key.startsWith("contact-") -> key.removePrefix("contact-").toULongOrNull()?.let { contacts[it] = value }
                    key.startsWith("tribe-") -> key.removePrefix("tribe-").toULongOrNull()?.let { tribes[it] = value }
                    key.startsWith("chat-contact-") -> key.removePrefix("chat-contact-").toULongOrNull()?.let { contactChats[it] = value }
                    key.startsWith("chat-tribe-") -> key.removePrefix("chat-tribe-").toULongOrNull()?.let { tribeChats[it] = value }
                }
            }
            return PushNameDirectory(contacts, tribes, contactChats, tribeChats).cleaned()
        }

        private fun Map<ULong, String>.cleanedNames(): Map<ULong, String> =
            mapNotNull { (idx, name) -> cleanName(name)?.let { idx to it } }.toMap()
    }
}

/// What a push opens when tapped.
data class PushRoute(val kind: Kind, val index: ULong, val chat: String?) {
    enum class Kind { CONTACT, TRIBE }
}

/// What a push shows, and where its tap goes (null: just open the app).
data class PushContent(val title: String, val body: String, val route: PushRoute?) {
    companion object {
        const val TITLE = "Peel"
        const val GENERIC_BODY = "You have new messages"
        val GENERIC = PushContent(TITLE, GENERIC_BODY, null)
    }
}

object PushNames {
    /// The text and route for a push whose sealed child index is [child].
    /// A missing key/child, a decrypt error or an index the map does not
    /// know all give [PushContent.GENERIC]. [decrypt] is the core's
    /// `decryptChildIndex` (injectable for tests). Never logs its inputs.
    fun resolve(
        child: String?,
        pushKey: String?,
        names: PushNameDirectory?,
        decrypt: (String, String) -> ULong = ::decryptChildIndex,
    ): PushContent {
        if (child.isNullOrEmpty() || pushKey.isNullOrEmpty() || names == null) return PushContent.GENERIC
        val index = try {
            decrypt(child, pushKey)
        } catch (e: Throwable) {
            return PushContent.GENERIC
        }
        names.contacts[index]?.let { name ->
            return PushContent(
                PushContent.TITLE, "${PushContent.GENERIC_BODY} from $name",
                PushRoute(PushRoute.Kind.CONTACT, index, names.contactChats[index]),
            )
        }
        names.tribes[index]?.let { name ->
            return PushContent(
                PushContent.TITLE, "${PushContent.GENERIC_BODY} in $name",
                PushRoute(PushRoute.Kind.TRIBE, index, names.tribeChats[index]),
            )
        }
        // Known only by chat id (its name was empty): generic text, but the tap still lands.
        names.contactChats[index]?.let {
            return PushContent.GENERIC.copy(route = PushRoute(PushRoute.Kind.CONTACT, index, it))
        }
        names.tribeChats[index]?.let {
            return PushContent.GENERIC.copy(route = PushRoute(PushRoute.Kind.TRIBE, index, it))
        }
        return PushContent.GENERIC
    }
}

/// Whether a push should reconnect and/or post its own notification.
data class PushPlan(val reconnect: Boolean, val postResolved: Boolean) {
    companion object {
        /// - No engine (locked or not running): nothing else can notify, so post.
        /// - An engine: reconnect if it is down (MQTT then notifies the real
        ///   message). While the app is in the background, ALSO post the
        ///   resolved one, so a message is never silent if MQTT is slow; it uses
        ///   the chat's own notification id, which MQTT's real notification
        ///   replaces: never two for one message. In front, the screen shows it.
        fun decide(engineOpen: Boolean, connected: Boolean, foreground: Boolean): PushPlan =
            if (!engineOpen) PushPlan(reconnect = false, postResolved = true)
            else PushPlan(reconnect = !connected, postResolved = !foreground)
    }
}
