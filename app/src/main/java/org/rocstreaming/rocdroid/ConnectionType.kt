package org.rocstreaming.rocdroid

import android.view.View

enum class ConnectionType (val serverRole: String, val buttonText: String) {

    SENDER("Reciever", "sending"),
    RECEIVER("Sender", "receiving")
}