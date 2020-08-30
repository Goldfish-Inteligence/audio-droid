package org.rocstreaming.rocdroid

import java.io.Serializable

data class StreamData (
    val ip: String,
val portAudio: Int,
val portError: Int,
val receiving: Boolean
) : Serializable