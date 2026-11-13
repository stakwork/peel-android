package com.sphinx.peel.ui.talk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.HiveException
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.TalkHttp
import com.sphinx.peel.engine.OkHttpTalk
import com.sphinx.peel.engine.TalkRefusal
import com.sphinx.peel.engine.TalkVoice
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/// Talk to Jamie: the voice call with an org's agent this device is on, or
/// starting, or why it could not start (iOS `TalkStore`). Beside the group
/// call held in `MainTabs`, not in it: a home names the room
/// ([TalkHome.startCall]), with no call link, call server or chat. The call
/// screen shows while there is a [home].
class TalkStore(context: Context, private val http: TalkHttp = OkHttpTalk()) {
    sealed class State {
        /// `POST /voice/calls` is on its way.
        object Starting : State()
        /// It could not start, in words.
        data class Failed(val reason: String) : State()
        /// In the room the home named.
        data class On(val session: TalkSession) : State()
    }

    private val appContext = context.applicationContext
    private val prefs = TalkVoice.prefs(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var starting: Job? = null

    /// The home being talked to; null with no call and no screen.
    var home by mutableStateOf<TalkHome?>(null)
        private set
    var state by mutableStateOf<State>(State.Starting)
        private set
    /// Jamie's voice as the screen shows it: the one kept on this device as a
    /// call starts, then the one the home says is in effect, then the member's own switches.
    var voice by mutableStateOf(TalkVoice.stored(prefs))
        private set

    /// The group call this device is on, if any, by its title: Talk waits
    /// until it is left rather than leaving it unasked.
    var otherCall: () -> String? = { null }
    /// The microphone permission: `CallController`'s, not an Activity's.
    var askMicPermission: suspend () -> Boolean = { false }
    /// Run in the call's own coroutine before the home is asked: `CallController`
    /// asks for the microphone and starts the call service, and returns why the
    /// call cannot start, or null.
    var preflight: suspend () -> String? = { null }
    /// The call started, changed, or ended: `CallController` keeps the service and its notification in step.
    var onChanged: () -> Unit = {}

    /// Calling or in the room: a group call waits until this is hung up.
    val isOn: Boolean get() = home != null && state !is State.Failed

    /// Calls [target]'s agent: the screen shows at once, saying it is
    /// calling, then the room once the home has named it, or why not. A call
    /// already on is left first.
    fun talk(target: TalkHome) {
        hangUp()
        home = target
        voice = TalkVoice.stored(prefs)
        val asked = voice
        otherCall()?.let { other ->
            state = State.Failed("Leave the call in $other first, then talk to Jamie.")
            return
        }
        state = State.Starting
        onChanged()
        starting = scope.launch {
            val refusal = preflight()
            if (refusal != null) {
                state = State.Failed(refusal)
                onChanged()
                return@launch
            }
            val next = try {
                val ticket = target.startCall(asked, http)
                val session = TalkSession(appContext, ticket, target, askMicPermission)
                session.onStatus = { onChanged() }
                // Switched while calling: the room is told once it is
                // connected. Else the screen shows the voice in effect.
                if (voice != asked) session.setVoice(voice) else ticket.voice?.let { voice = it }
                State.On(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TalkRefusal) {
                State.Failed(e.message ?: "${target.name} could not start the call.")
            } catch (e: HiveException) {
                State.Failed(e.message ?: "${target.name} could not start the call.")
            } catch (e: IOException) {
                State.Failed("Peel could not reach ${target.name}: ${e.message ?: e.javaClass.simpleName}.")
            } catch (e: Exception) {
                State.Failed("Peel could not start the call: ${e.message ?: e.javaClass.simpleName}.")
            }
            state = next
            onChanged()
        }
    }

    /// The toggle: Jamie's other voice, kept on this device for the next call
    /// and, on a call, asked of the agent from its next sentence.
    fun toggleVoice() {
        voice = voice.flipped
        voice.store(prefs)
        (state as? State.On)?.session?.setVoice(voice)
    }

    /// The same home again, after a failure or a call that ended.
    fun tryAgain() {
        home?.let { talk(it) }
    }

    /// Leaves the room, if one was joined, and the screen goes.
    fun hangUp() {
        starting?.cancel()
        starting = null
        (state as? State.On)?.session?.leave()
        home = null
        state = State.Starting
        onChanged()
    }
}
