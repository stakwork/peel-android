package com.sphinx.peel.ui.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import com.sphinx.peel.engine.OrgPageRequest

/// What a link in the app does (iOS `\.openURL`): provided once, at `MainTabs`, so a tapped Hive app link opens its page from any
/// message, deliverable or card; the default is the system's, as before.
fun interface LinkOpener {
    fun open(url: String)
}

val LocalLinkOpener = staticCompositionLocalOf<LinkOpener?> { null }

/// Opens an org's page over everything (iOS `router.orgPage = page`): provided at `MainTabs`, which shows the page host and closes
/// it when its workspace is no longer in use. The default opens nothing.
val LocalPageOpener = staticCompositionLocalOf<(OrgPageRequest) -> Unit> { {} }

/// An address in the system's browser (a link that is not Peel's to open).
fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nothing to open it: it simply does not open.
    }
}
