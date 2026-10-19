package com.example.disasterconnect.ble

/**
 * Orders the client side of the HELLO / ACK handshake (rules.md §13). Android runs one GATT
 * operation at a time, and the server's HELLO notification can arrive before the client's
 * subscription write has completed; writing the ACK then is refused as busy. The ACK is
 * therefore written only once HELLO has arrived AND the subscription write has finished,
 * and only once. The bytes on the wire are unchanged.
 */
internal class HandshakeSequencer {

    private var subscribing = false
    private var helloReceived = false
    private var ackSent = false

    @Synchronized
    fun reset() {
        subscribing = false
        helloReceived = false
        ackSent = false
    }

    @Synchronized
    fun onSubscribeStarted() {
        subscribing = true
    }

    /** The subscription write finished. True when the ACK should be written now. */
    @Synchronized
    fun onSubscribed(): Boolean {
        subscribing = false
        return takeAck()
    }

    /** HELLO arrived. True when the ACK should be written now. */
    @Synchronized
    fun onHello(): Boolean {
        helloReceived = true
        return takeAck()
    }

    private fun takeAck(): Boolean =
        (helloReceived && !subscribing && !ackSent).also { if (it) ackSent = true }
}
