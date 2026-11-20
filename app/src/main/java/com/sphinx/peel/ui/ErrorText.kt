package com.sphinx.peel.ui

import android.content.Context
import androidx.annotation.StringRes
import com.sphinx.peel.R
import com.sphinx.peel.engine.InviteProblem
import com.sphinx.peel.engine.SignupRules

/// The words for a failure the engine or account code raised in English. That code is pure (no `Context`,
/// JVM-tested), so it keeps its English messages; the UI maps the ones it knows, by exact text, to a string
/// resource, and shows any other message as it came (a server's or the system's own words) -- behaviour is
/// unchanged, only the known messages follow the device language.
/// docs/phases/52-localization-es.md.
object ErrorText {
    private val known: Map<String, Int> = mapOf(
        InviteProblem.NOT_AN_INVITE.message!! to R.string.err_not_an_invite,
        InviteProblem.TOO_LONG.message!! to R.string.err_invite_too_long,
        InviteProblem.NO_INVITER.message!! to R.string.err_invite_no_inviter,
        InviteProblem.NO_SERVER.message!! to R.string.err_invite_no_server,
        SignupRules.NOT_CONFIRMED to R.string.err_not_confirmed,
        "Enter a name." to R.string.err_enter_name,
        "The invite names a server Peel can't use." to R.string.err_unusable_server,
        "Could not save the account." to R.string.err_could_not_save,
        "The server did not answer in time." to R.string.err_server_timeout,
        "No registration answer came back in time." to R.string.err_no_registration_answer,
        "Could not connect to the server." to R.string.err_could_not_connect,
        "Restoring stopped." to R.string.err_restoring_stopped,
        "That is a tribe link, not a person." to R.string.err_tribe_link,
        "That is not contact info, an invite or a Sphinx link." to R.string.err_not_contact_info,
    )

    @StringRes
    fun resourceFor(message: String?): Int? = message?.let { known[it] }

    /// `message` in the device language when it is a known one; otherwise as it is. A null message gives `fallback`.
    fun text(context: Context, message: String?, @StringRes fallback: Int): String =
        resourceFor(message)?.let { context.getString(it) } ?: message ?: context.getString(fallback)

    fun text(context: Context, error: Throwable, @StringRes fallback: Int): String = text(context, error.message, fallback)
}
