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
    fun onAudioStream(
        recvAudioPort: Int,
        recvRepairPort: Int,
        sendAudioPort: Int,
        sendRepairPort: Int
    )

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
    private var discovery: Boolean = false
    private var socket: Socket? = null

    private var host: String = ""
    private var port: Int = 0

    // cant get this to work without nullable
    private var nsdManager: NsdManager? = null
    private var autoHost: String = ""
    private var autoPort: Int = 0

    private val resolveListener: NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {

            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                autoHost = serviceInfo?.host?.hostName ?: ""
                autoPort = serviceInfo?.port ?: 0
                if (autoPort != 0 && autoHost.isNotEmpty()) {
                    callbacks.onServerDiscovered(autoHost, autoPort)
                    nsdManager?.stopServiceDiscovery(discoveryListener)
                    discovery = false
                }
            }

        }

    private val discoveryListener: NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != "_geckoaudio._tcp.") {
                    return
                }

                nsdManager?.resolveService(service, resolveListener)
            }

            override fun onStopDiscoveryFailed(p0: String?, p1: Int) {
            }

            override fun onStartDiscoveryFailed(p0: String?, p1: Int) {
            }

            override fun onDiscoveryStarted(p0: String?) {
                return
            }

            override fun onDiscoveryStopped(p0: String?) {
                return
            }

            override fun onServiceLost(p0: NsdServiceInfo?) {

            }
        }

    init {
        this.nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    fun sendBatteryLevel(level: Double, isCharging: Boolean) {
        val command = JSONObject()
        command.put("type", "BatteryLevel")
        command.put("level", level)
        command.put("is_charging", isCharging)
        socket?.getOutputStream()?.write(command.toString().toByteArray())
    }

    fun sendLogMsg(message: String) {
        val command = JSONObject()
        command.put("type", "LogMsg")
        command.put("message", message)

        socket?.getOutputStream()?.write(command.toString().toByteArray())
    }

    fun sendDisplayName(displayName: String) {
        val command = JSONObject()
        command.put("type", "DisplayName")
        command.put("display_name", displayName)

        socket?.getOutputStream()?.write(command.toString().toByteArray())
    }

    fun sendAudioStream(
        recvAudioPort: Int,
        recvRepairPort: Int,
        sendAudioPort: Int,
        sendRepairPort: Int
    ) {
        val command = JSONObject()
        command.put("type", "AudioStream")
        command.put("recv_audio_port", recvAudioPort)
        command.put("recv_repair_port", recvRepairPort)
        command.put("send_audio_port", sendAudioPort)
        command.put("send_repair_port", sendRepairPort)

        socket?.getOutputStream()?.write(command.toString().toByteArray())
    }

    fun sendMuteAudio(sendMute: Boolean, recvMute: Boolean) {
        val command = JSONObject()
        command.put("type", "MuteAudio")
        command.put("send_mute", sendMute)
        command.put("recv_mute", recvMute)

        socket?.getOutputStream()?.write(command.toString().toByteArray())
    }

    fun sendTransmitAudio(sendAudio: Boolean, recvAudio: Boolean) {
        val command = JSONObject()
        command.put("type", "TransmitAudio")
        command.put("send_audio", sendAudio)
        command.put("recv_audio", recvAudio)

        socket?.getOutputStream()?.write(command.toString().toByteArray())
    }

    fun searchServer() {
        if (!discovery)
            nsdManager?.discoverServices(
                "_geckoaudio._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        discovery = true
    }

    fun stopConnection() {
        if (discovery)
            nsdManager?.stopServiceDiscovery(discoveryListener)
        socket?.close()

    }

    fun connected(): Boolean {
        return socket?.isConnected ?: false
    }

    fun connect(host: String, port: Int) {
        socket = Socket(host, port)

        val command = JSONObject()
        command.put("type", "Hello")
        command.put("client_name", deviceId)

        socket?.getOutputStream()?.write(command.toString().toByteArray())

        socket?.getOutputStream()
    }
}