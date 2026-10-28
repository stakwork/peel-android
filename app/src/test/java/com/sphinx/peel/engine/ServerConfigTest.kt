package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// org.json.JSONObject's Android stub throws on a plain JVM unit test;
// Robolectric shadows it with the real implementation, same reason
// StorageTest needs it for android.database.sqlite.
@RunWith(RobolectricTestRunner::class)
class ServerConfigTest {
    @Test
    fun `parses the production config shape`() {
        val config = ServerConfig.parse(
            """{"tribe":"","tribe_host":"tribes.v2.sphinx.chat","router":"mixer.router1.sphinx.chat",""" +
                """"default_lsp":"mqtt-broker.v2.sphinx.chat:8883","lsps":["mqtt-broker.v2.sphinx.chat:8883"]}""",
        )
        assertEquals("mqtt-broker.v2.sphinx.chat:8883", config.defaultLsp)
        assertEquals("tribes.v2.sphinx.chat", config.tribeHost)
    }

    @Test
    fun `an empty tribe_host is treated as absent`() {
        val config = ServerConfig.parse("""{"default_lsp":"host:8883","tribe_host":""}""")
        assertNull(config.tribeHost)
    }
}
