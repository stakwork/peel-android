package com.sphinx.peel.ui.call

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sphinx.peel.engine.BoxCalls
import com.sphinx.peel.engine.BoxCallsClient
import com.sphinx.peel.engine.BoxLink
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.OkHttpBox
import com.sphinx.peel.engine.TalkHome
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/// A box's Jamie in an encrypted group call, live (docs/phases/56-box-group-calls.md, "Checked"): against a real strut with
/// stakwork/strut#881 forwarding to a box's voice service (sphinx-voice#45–#47) on a real LiveKit. Skipped unless the run names the
/// box: `-e boxStrut http://127.0.0.1:3077 -e boxToken strut-person.… [-e callServer 127.0.0.1:7880]` (`adb reverse` for the
/// strut and the LiveKit; the call server is the LiveKit itself, so its tickets' checks take `ws://127.0.0.1:7880`).
///
/// The box is asked in with recording on (and refuses the same room again), then two people join on their own tickets from it, with
/// the call's key: the room's encryption is on for both, each sees Jamie and Jamie recording on the canvas, and what one types
/// reaches the other (each takes only what came encrypted, so this fails if Peel sent it in the clear). The box's voice service logs
/// the line too ("text message from Ada"): it decrypted what Android sent.
@RunWith(AndroidJUnit4::class)
class BoxGroupCallLiveTest {
    @Test fun aBoxJoinsAnEncryptedCallAndPeopleJoinOnItsTickets() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val strut = args.getString("boxStrut")
        val token = args.getString("boxToken")
        assumeTrue("no box named (-e boxStrut, -e boxToken)", strut != null && token != null)
        val callServer = args.getString("callServer") ?: "127.0.0.1:7880"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)

        val now = Instant.now()
        val box = BoxLink(strut!!, "ada", "Ada", token!!, now.plusSeconds(3600), linkedAt = now, renewedAt = now)
        val client = BoxCallsClient(box, OkHttpBox())

        // The menu's rule: group-ready, on the call server's LiveKit.
        val status = client.status()
        assertTrue("$status", BoxCalls.offers(status, callServer))

        // The box first, recording on the canvas; a second ask for the same room is the box's 409.
        val link = CallLink.new(callServer, audioOnly = true, encryptionKey = TalkHome.newKey())
        val agent = client.join(link.room, link.encryptionKey!!, record = true)
        assertTrue(agent.isNotEmpty())
        val again = runCatching { client.join(link.room, link.encryptionKey!!, record = false) }.exceptionOrNull()
        assertEquals("Jamie from 127.0.0.1 is already in this call.", again?.message)
        // No ticket for a room the box is not in.
        assertNull(client.ticket("sphinx.call.not-there", "Ada"))

        // The link as posted, read back as anyone in the chat reads it.
        val posted = CallLink.inMessage(link.message, isCallType = true, server = callServer)!!
        assertEquals(link.encryptionKey, posted.encryptionKey)

        val adaTicket = client.ticket(posted.room, "Ada")!!.callTicket(callServer, "Ada")
        val bobTicket = client.ticket(posted.room, "Bob")!!.callTicket(callServer, "Bob")
        assertTrue(adaTicket.identity.startsWith("box_"))
        assertNotEquals(adaTicket.identity, bobTicket.identity)

        val (ada, bob) = withContext(Dispatchers.Main) {
            fun session(ticket: com.sphinx.peel.engine.CallTicket) =
                CallSession(context, ticket, posted, "live", "Live", camera = false, askMicPermission = { true }, askCameraPermission = { false })
            session(adaTicket) to session(bobTicket)
        }
        try {
            suspend fun until(what: String, seconds: Long = 30, check: () -> Boolean) {
                val ok = withTimeoutOrNull(seconds * 1000) {
                    while (!withContext(Dispatchers.Main) { check() }) delay(250)
                    true
                }
                val seen = withContext(Dispatchers.Main) {
                    "Ada's chat ${ada.chatMessages.map { it.name + ": " + it.text }}, Bob's ${bob.chatMessages.map { it.name + ": " + it.text }}, " +
                        "notices ${ada.encryptionNotice} / ${bob.encryptionNotice}"
                }
                assertNotNull("timed out waiting for $what ($seen)", ok)
            }
            until("both connected") { ada.state == CallConnectionState.CONNECTED && bob.state == CallConnectionState.CONNECTED }
            assertNull(ada.encryptionNotice)
            assertNull(bob.encryptionNotice)
            until("Jamie in the call") { ada.tiles.any { it.person.isJamie } && bob.tiles.any { it.person.isJamie } }
            until("Jamie recording on the canvas") { ada.recordedOnCanvas && bob.recordedOnCanvas }

            withContext(Dispatchers.Main) { ada.sendChat("Hello from Android") }
            until("Ada's line at Bob's") { bob.chatMessages.any { it.text == "Hello from Android" && !it.isYou } }
            // Not checked: Jamie's own chat lines (its recording notice). They arrive decrypted, but livekit-android 2.29.2 finds a
            // data packet's sender only by its participant SID, which rtc-node leaves empty, so the session drops them as from
            // nobody (docs/phases/56-box-group-calls.md, "Found").
            assertNull(withContext(Dispatchers.Main) { ada.encryptionNotice ?: bob.encryptionNotice })
        } finally {
            withContext(Dispatchers.Main) {
                ada.leave()
                bob.leave()
            }
        }
    }
}
