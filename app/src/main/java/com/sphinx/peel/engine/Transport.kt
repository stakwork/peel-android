package com.sphinx.peel.engine

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Inbound(val topic: String, val payload: ByteArray)

/// One MQTT connection. The login is a signed timestamp (`Engine.accountKey`
/// as the client ID, a fresh time as the username, its signature as the
/// password), so a connection is never reused: reconnecting means a fresh
/// Transport. Ported from PeelCore's Transport.swift, on the HiveMQ MQTT
/// client (Eclipse Paho's Android client needs a bound Service; this is a
/// plain library call like MQTTNIO's on the Mac/iPhone side).
///
/// HiveMQ's own `automaticReconnect()` is deliberately not used: it
/// reconnects the socket but does not redo our own topic subscriptions
/// (the broker forgets them on a clean-session reconnect), so a silent
/// auto-reconnect would leave the app "connected" but deaf. Instead
/// [onUnexpectedDisconnect] fires once, [Engine]'s own reconnect loop
/// (`connect`) builds a fresh `Transport` and redoes the whole handshake —
/// PeelCore's own `connectionClosed`/`reconnectLoop`, just driven by this
/// callback as the drop signal instead of PeelCore's own close-listener
/// plus application-level ping.
class Transport(
    private val server: ServerAddress,
    private val clientID: String,
    private val username: String,
    private val password: String,
    private val onUnexpectedDisconnect: () -> Unit = {},
) {
    /// Whether the client can take a publish or subscribe right now —
    /// checked before every send so a dead connection raises a clear,
    /// catchable [IllegalStateException] with a real stack trace, never
    /// HiveMQ's own `MqttClientStateException` (a reused "hot path"
    /// exception with none, from deep inside the library, on whatever
    /// coroutine happened to call send() — unreadable in a crash report,
    /// and if nothing downstream happens to catch it, fatal).
    val isConnected: Boolean get() = client.state.isConnected

    private val client: Mqtt3AsyncClient = run {
        // Release builds refuse plaintext MQTT; surfaced as an ordinary
        // exception: Engine.connect() -> connectAndRegister() catches it and
        // reports ConnectionState.Failed(message) to the UI.
        Log.d(PeelLog.TAG, "Transport: building client ${server.host}:${server.port} tls=${server.tls} debugBuild=${com.sphinx.peel.BuildConfig.DEBUG} buildType=${com.sphinx.peel.BuildConfig.BUILD_TYPE}")
        if (!NetworkPolicy.tlsAllowed(server.tls, com.sphinx.peel.BuildConfig.DEBUG)) {
            throw IllegalStateException("${server.host} is configured without TLS; plaintext MQTT is not allowed in release builds")
        }
        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientID)
            .serverHost(server.host)
            .serverPort(server.port)
            .addDisconnectedListener { context ->
                // USER is this Transport's own close() (client.disconnect()) —
                // the one drop that is never "unexpected". Automatic
                // reconnect defaults to off (HiveMQ's own
                // MqttClientAutoReconnectImpl.DEFAULT_RECONNECT is false
                // unless automaticReconnect() is called, which this builder
                // never does), so the socket stays down either way; this
                // only decides whether Engine hears about it.
                val unexpected = context.source != MqttDisconnectSource.USER
                Log.w(PeelLog.TAG, "Transport: disconnected source=${context.source} unexpected=$unexpected cause=${context.cause}", context.cause)
                if (unexpected) onUnexpectedDisconnect()
            }
        if (server.tls) {
            // Default SSL config: system trust store. Hostname verification
            // is on by default — checked in hivemq-mqtt-client 1.4.1's
            // bytecode (no source jar): MqttSslInitializer, with no custom
            // HostnameVerifier set, calls SSLParameters
            // .setEndpointIdentificationAlgorithm("HTTPS") so the TLS stack
            // checks the certificate against server.host during the
            // handshake, and falls back to HttpsURLConnection's default
            // verifier. Not exercised against a live broker. There is no
            // untrusted-certificate escape hatch; never set a custom
            // verifier or TrustManager here.
            builder.sslWithDefaultConfig()
        }
        builder.buildAsync()
    }

    /// Messages from the broker, from the moment this is collected.
    /// Completes when the connection closes.
    fun inbound(): Flow<Inbound> = callbackFlow {
        client.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            trySend(Inbound(publish.topic.toString(), publish.payloadAsBytes))
        }
        awaitClose { }
    }

    suspend fun connect() {
        Log.d(PeelLog.TAG, "Transport.connect: ${server.host}:${server.port} tls=${server.tls}")
        try {
            connectInner()
            Log.d(PeelLog.TAG, "Transport.connect: success")
        } catch (e: Exception) {
            Log.e(PeelLog.TAG, "Transport.connect failed (${server.host}:${server.port} tls=${server.tls}): ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }

    private suspend fun connectInner() {
        client.connectWith()
            // The MQTT keep-alive: a PINGREQ every 30 seconds, and the
            // client drops the connection (-> onLost) when none is answered.
            .keepAlive(KEEP_ALIVE_SECONDS)
            .simpleAuth()
            .username(username)
            .password(password.toByteArray(Charsets.UTF_8))
            .applySimpleAuth()
            .send()
            .await()
    }

    suspend fun subscribe(topics: List<String>) {
        check(isConnected) { "MQTT client is not connected." }
        Log.d(PeelLog.TAG, "Transport.subscribe: ${topics.size} topic(s) on ${server.host}:${server.port}")
        for (topic in topics) {
            client.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_MOST_ONCE)
                .send()
                .await()
        }
        Log.d(PeelLog.TAG, "Transport.subscribe: success")
    }

    suspend fun publish(topic: String, payload: ByteArray) {
        check(isConnected) { "MQTT client is not connected." }
        try {
            client.publishWith()
                .topic(topic)
                .payload(payload)
                .qos(MqttQos.AT_MOST_ONCE)
                .send()
                .await()
            Log.d(PeelLog.TAG, "Transport.publish: success (${payload.size} bytes) to ${server.host}:${server.port}")
        } catch (e: Exception) {
            Log.e(PeelLog.TAG, "Transport.publish failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }

    suspend fun close() {
        client.disconnect().await()
    }
}

/// `CompletableFuture.await()` without pulling in kotlinx-coroutines-jdk8
/// for this one call.
private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error != null) continuation.resumeWithException(error) else continuation.resume(value)
    }
    continuation.invokeOnCancellation { cancel(true) }
}

private const val KEEP_ALIVE_SECONDS = 30
