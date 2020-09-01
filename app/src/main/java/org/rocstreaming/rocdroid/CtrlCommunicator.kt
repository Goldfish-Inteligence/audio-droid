package org.rocstreaming.rocdroid

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

interface CtrlCallback {
    fun onServerDiscovered(host: String, port: Int)

    fun onDisplayName(displayName: String)
    fun onAudioStream(recvAudioPort: Int, recvRepairPort: Int, sendAudioPort: Int, sendRepairPort: Int)
    fun onMuteAudio(sendMute: Boolean, recvMute: Boolean)
    fun onTransmitAudio(sendAudio: Boolean, recvAudio: Boolean)
    @ExperimentalTime
    fun onBatteryLogInterval(batterLogInterval: Duration)
}

/**
 * Handles communication with gecko_audio_ctrl
 * Provide callback interface in constructor. If a the a callback is called that corresponds to a
 * server command, it is expected that the clients calls the corresponding set method to
 * acknowledge that ot accepts the command.
 *
 * Usually there is no need to manually specify host and port. You might rely on searchServer().
 * This might not work if inside managed networks (Though we are creating a sperate wifi for
 * the mics even in the "Mathebau")
 *
 * All method calls are non-blocking and threadsafe.
 * TODO: That is a lie (see below)
 *
 * TODO: This class should handle somewhat automatic reconnect (though things might be different if for example the user disables wifi)
 * TODO: Actually understand kotlin and objects, inner classes, ..., I do not even know what to google for
 * TODO: Implement missing methods
 * TODO: Implement reading from server stream to execute callback methods
 * TODO: Implement a very simple state machine that is queryable (connecting, connected, fatal, retrying, ...)\
 * TODO: Implement a send thread for networking
 */
class CtrlCommunicator(// cant get this to work without nullable
    private var callbacks: CtrlCallback, private val deviceId: String, context: Context
) {
    private var socket: Socket? = null

    private var host: String = ""
    private var port: Int = 0

    // cant get this to work without nullable
    private var nsdManager: NsdManager? = null
    private var autoHost: String = ""
    private var autoPort: Int = 0

    private val discoveryListener = object : NsdManager.DiscoveryListener {

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType != "_geckoaudio._tcp") {
                return
            }

            nsdManager?.stopServiceDiscovery(this)

            autoHost = service.host.hostName
            autoPort = service.port
            callbacks.onServerDiscovered(autoHost, autoPort)
        }

        override fun onStopDiscoveryFailed(p0: String?, p1: Int) {
            TODO("Not yet implemented")
        }

        override fun onStartDiscoveryFailed(p0: String?, p1: Int) {
            TODO("Not yet implemented")
        }

        override fun onDiscoveryStarted(p0: String?) {
            return
        }

        override fun onDiscoveryStopped(p0: String?) {
            return
        }

        override fun onServiceLost(p0: NsdServiceInfo?) {
            TODO("Not yet implemented")
        }
    }

    init {
        this.nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    fun sendBatteryLevel(level: Double, isChargin: Boolean) {
        TODO("Not yet implemented")
    }

    fun sendLogMsg(message: String) {
        TODO("Not yet implemented")
    }

    fun sendDisplayName(displayName: String) {
        val command = JSONObject();
        command.put("type", "DisplayName")
        command.put("display_name", displayName)

        socket?.getOutputStream()?.write(command.toString().toByteArray())
    }

    fun sendAudioStream(recvAudioPort: Int, recvRepairPort: Int, sendAudioPort: Int, sendRepairPort: Int) {
        TODO("Not yet implemented")
    }

    fun sendMuteAudio(sendMute: Boolean, recvMute: Boolean) {
        TODO("Not yet implemented")
    }

    fun sendTransmitAudio(sendAudio: Boolean, recvAudio: Boolean) {
        TODO("Not yet implemented")
    }

    fun searchServer() {
        nsdManager?.discoverServices("_geckoaudio._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun connect(host: String, port: Int) {
        socket = Socket(host, port)

        val command = JSONObject();
        command.put("type", "Hello")
        command.put("client_name",  deviceId)

        socket?.getOutputStream()?.write(command.toString().toByteArray())

        socket?.getOutputStream()
    }
}