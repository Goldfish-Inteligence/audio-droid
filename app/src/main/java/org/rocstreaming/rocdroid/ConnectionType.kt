package org.rocstreaming.rocdroid

enum class ConnectionType(val serverRole: String, val buttonText: String) {

    SENDER("Reciever", "sending"),
    RECEIVER("Sender", "receiving")

}