package org.rocstreaming.rocdroid

import java.io.Serializable

data class StreamData(
    val ip: String,
    val portAudioReceive: Int,
    val portErrorReceive: Int,
    val portAudioSend: Int,
    val portErrorSend: Int,
    val receiving: Boolean,
    val sending: Boolean
) : Serializable {
    fun modified(
        ip: String = this.ip,
        portAudioReceive: Int = this.portAudioReceive,
        portErrorReceive: Int = this.portErrorReceive,
        portAudioSend: Int = this.portAudioSend,
        portErrorSend: Int = this.portErrorSend,
        receiving: Boolean = this.receiving,
        sending: Boolean = this.sending
    ): StreamData {
        return StreamData(
            ip, portAudioReceive, portErrorReceive, portAudioSend, portErrorSend, receiving, sending
        )
    }
}