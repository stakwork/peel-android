package com.sphinx.peel.engine

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
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
/// Nothing calls this yet: Engine makes no server calls, so there is
/// nothing to subscribe or publish for. Not exercised against a broker
/// yet either (ServerAddressTest covers the one pure piece, URL parsing);
/// a real test needs something to connect to, which is its own decision
/// (a local Mosquitto via Testcontainers, needs Docker running; or the
/// account's own server once one is configured).
class Transport(
    private val server: ServerAddress,
    private val clientID: String,
    private val username: String,
    private val password: String,
    private val allowUntrustedCertificate: Boolean = false,
) {
    private val client: Mqtt3AsyncClient = run {
        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientID)
            .serverHost(server.host)
            .serverPort(server.port)
        if (server.tls) {
            // allowUntrustedCertificate has no effect yet: a dev build
            // against a self-signed test server needs a custom
            // TrustManager here (builder.sslConfig { trustManagerFactory =
            // ... }), added once there is such a server to verify it
            // against.
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
        client.connectWith()
            .simpleAuth()
            .username(username)
            .password(password.toByteArray(Charsets.UTF_8))
            .applySimpleAuth()
            .send()
            .await()
    }

    suspend fun subscribe(topics: List<String>) {
        for (topic in topics) {
            client.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_MOST_ONCE)
                .send()
                .await()
        }
    }

    suspend fun publish(topic: String, payload: ByteArray) {
        client.publishWith()
            .topic(topic)
            .payload(payload)
            .qos(MqttQos.AT_MOST_ONCE)
            .send()
            .await()
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
