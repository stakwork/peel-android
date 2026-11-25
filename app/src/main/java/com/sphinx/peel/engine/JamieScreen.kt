package com.sphinx.peel.engine

// Jamie's screen as the app itself, for a person linked to the box (stadeum plans/call-canvas.md §6.5; docs/phases/57-live-canvas-tile.md).
// A box's Jamie shares an app as a screen-share video, and says which app in its `screen` participant attribute: strut's deep link on
// the box, `https://strut.<host>/?app=<name>&input=<query>`, empty while it shares nothing. When that address is a box this phone is
// linked to, Jamie's screen tile loads the app there with the person's own token instead of the video, as any of the box's apps opens
// ([BoxLink.home]). Pure, JVM-tested (`JamieScreenTest`); the tile is `ui/call/CallScreen`.
//
// The attribute goes through LiveKit's server, outside the call's encryption, and anyone in the call could name themselves Jamie. It is
// only ever a name: it opens nothing but one of strut's app links ([StrutAppLink]) on a box the person already linked, the same page a
// link to that app opens, and nothing at all anywhere else.

object JamieScreen {
    /// The participant attribute Jamie's voice service names its screen's app in.
    const val ATTRIBUTE = "screen"

    /// The app link a participant named [name] with [attributes] gives for its screen: Jamie's (named Jamie, with the attribute its voice
    /// service sets on joining, [CallRoomMember.isJamie]) `screen`, when it names one. Null for anyone else, or none.
    fun link(name: String?, attributes: Map<String, String>): String? {
        if (!CallRoomMember("", name, null, attributes).isJamie) return null
        return attributes[ATTRIBUTE]?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LINK }
    }

    /// The box and the app Jamie's screen [link] names, when the box is one of [links] (the account's linked boxes): strut's UI on the
    /// box's own address, naming one of its apps by `?app=`. Null for anything else, so the tile keeps the video.
    fun place(link: String?, links: List<BoxLink>): Place? {
        if (link.isNullOrEmpty()) return null
        for (box in links) {
            val home = box.home
            if (!home.serves(link)) continue
            val app = StrutAppLink.parse(link, home.folder, StrutAppLink.Source.Org) ?: continue
            return Place(box, app)
        }
        return null
    }

    /// A box's app, at the view Jamie's link names.
    data class Place(val box: BoxLink, val app: StrutAppLink) {
        override fun toString() = "${box.host} ${app.app.app}"
    }

    /// The longest link taken: an address with an app's input ([StrutAppLink.LONGEST_INPUT]) and room to spare.
    private const val MAX_LINK = 4_096
}
