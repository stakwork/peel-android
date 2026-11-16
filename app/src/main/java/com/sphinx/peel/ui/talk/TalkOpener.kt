package com.sphinx.peel.ui.talk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.HivePeer
import com.sphinx.peel.engine.OrgPageRequest

/// What a card tapped on the Talk call screen opens (iOS `TalkOpener`, `TalkOpened`). iOS's `opened` is a page or a link, each shown in
/// a sheet over the call; here a page is [page] (shown by the one page host over the call screen) and a link has no sheet of ours to
/// track: Android has no in-app browser (androidx.browser is not a dependency), so [link] hands the address to the system browser and
/// keeps nothing. The call goes on behind both (it has its foreground service).
class TalkOpener {
    var page by mutableStateOf<OrgPageRequest?>(null)
        private set

    /// iOS `opener.opened = .page(page)`.
    fun showPage(request: OrgPageRequest) {
        page = request
    }

    /// iOS `opener.opened = .link(url)`: the address to open outside the app, as it is (no Hive handling on the call screen).
    fun link(url: String): String = url

    /// iOS `.onChange(of: live)`: a page whose workspace is no longer in use goes, with its token.
    fun workspacesChanged(live: List<HivePeer>) {
        val shown = page ?: return
        if (!shown.isLive(live)) page = null
    }

    /// The sheet's close, or the call screen going away.
    fun close() {
        page = null
    }
}
