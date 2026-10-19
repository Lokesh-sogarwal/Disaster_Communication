package com.example.disasterconnect.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * Regression (two-phone QA, OnePlus CPH2717 → vivo V2509, Android 16): the vivo's HELLO
 * notification arrived 96 ms after the OnePlus started its subscription write and before that
 * write completed; the immediate ACK write was refused and the handshake failed.
 */
class HandshakeSequencerTest {

    private val sequencer = HandshakeSequencer()


    @Test
    fun helloArrivingBeforeTheSubscriptionWriteCompletesWaitsForIt() {

        sequencer.onSubscribeStarted()

        assertFalse(sequencer.onHello())
        assertTrue(sequencer.onSubscribed())
    }


    @Test
    fun helloAfterTheSubscriptionWriteCompletesAcksAtOnce() {

        sequencer.onSubscribeStarted()

        assertFalse(sequencer.onSubscribed())
        assertTrue(sequencer.onHello())
    }


    @Test
    fun ackIsWrittenOnlyOnceEvenIfHelloRepeats() {

        sequencer.onSubscribeStarted()
        sequencer.onSubscribed()

        assertTrue(sequencer.onHello())
        assertFalse(sequencer.onHello())
        assertFalse(sequencer.onSubscribed())
    }


    @Test
    fun resetStartsANewHandshakeForTheNextConnection() {

        sequencer.onSubscribeStarted()
        sequencer.onSubscribed()
        sequencer.onHello()

        sequencer.reset()
        sequencer.onSubscribeStarted()

        assertFalse(sequencer.onHello())
        assertTrue(sequencer.onSubscribed())
    }
}
