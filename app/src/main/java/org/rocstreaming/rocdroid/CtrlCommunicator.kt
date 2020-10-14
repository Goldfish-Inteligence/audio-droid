package org.rocstreaming.rocdroid

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

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

enum class ConnectionState {
    UNCONNECTED,
    CONNECTING,
    CONNECTED,
    RETRYING,
    FATAL
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
 */
class CtrlCommunicator(// cant get this to work without nullable
    private var callbacks: CtrlCallback, private val deviceId: String, context: Context
) {
    private var recvThread: Thread? = null
    private var socketThread: Thread? = null
    var state: ConnectionState = ConnectionState.UNCONNECTED
        private set
    private var socket: Socket? = null

    private var host: String = ""
    private var port: Int = 0

    // cant get this to work without nullable
    private var nsdManager: NsdManager? = null
    private var autoHost: String = ""
    private var autoPort: Int = 0

    private val commands: BlockingQueue<JSONObject?> = LinkedBlockingQueue()

    private val resolveListener: NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                state = ConnectionState.UNCONNECTED
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                autoHost = serviceInfo?.host?.hostAddress ?: ""
                autoPort = serviceInfo?.port ?: 0
                if (autoPort != 0 && autoHost.isNotEmpty()) {
                    state = ConnectionState.CONNECTED
                    callbacks.onServerDiscovered(autoHost, autoPort)
                    nsdManager?.stopServiceDiscovery(discoveryListener)
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

        commands.offer(command)
    }

    fun sendLogMsg(message: String) {
        val command = JSONObject()
        command.put("type", "LogMsg")
        command.put("message", message)

        commands.offer(command)
    }

    fun sendDisplayName(displayName: String) {
        val command = JSONObject()
        command.put("type", "DisplayName")
        command.put("display_name", displayName)

        commands.offer(command)
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

        commands.offer(command)
    }

    fun sendMuteAudio(sendMute: Boolean, recvMute: Boolean) {
        val command = JSONObject()
        command.put("type", "MuteAudio")
        command.put("send_mute", sendMute)
        command.put("recv_mute", recvMute)

        commands.offer(command)
    }

    fun sendTransmitAudio(sendAudio: Boolean, recvAudio: Boolean) {
        val command = JSONObject()
        command.put("type", "TransmitAudio")
        command.put("send_audio", sendAudio)
        command.put("recv_audio", recvAudio)

        commands.offer(command)
    }

    fun searchServer() {
        if (state in arrayOf(ConnectionState.UNCONNECTED, ConnectionState.RETRYING)) {
            nsdManager?.discoverServices(
                "_geckoaudio._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            state = ConnectionState.CONNECTING
        }
    }

    fun stopConnection() {
        if (state == ConnectionState.CONNECTING || state == ConnectionState.RETRYING)
            nsdManager?.stopServiceDiscovery(discoveryListener)
        socket?.close()
        socket = null
        socketThread = null
    }


    @OptIn(ExperimentalTime::class)
    fun connect(host: String, port: Int) {
        socket = Socket(host, port)

        val command = JSONObject()
        command.put("type", "Hello")
        command.put("client_name", deviceId)

        commands.offer(command)

        val output = socket?.getOutputStream()
        val input =
            if (socket?.getInputStream() == null) null
            else BufferedReader(InputStreamReader(socket!!.getInputStream()))
        socketThread = thread(start = true) {
            while (socket?.isConnected == true) {
                try {
                    val msg = commands.poll(500, TimeUnit.MILLISECONDS)?.toString() ?: ""
                    output?.write(msg.toByteArray())
                } catch (e: InterruptedException) {
                    // do nothing
                }
            }
            state = ConnectionState.RETRYING
            var errorCount = 0
            while (state == ConnectionState.RETRYING || state == ConnectionState.CONNECTING) {
                searchServer()
                Thread.sleep(Math.pow(4.0, errorCount.toDouble()).toLong())
                nsdManager?.stopServiceDiscovery(discoveryListener)
                errorCount++
            }
        }
        recvThread = thread(start = true) {
            var msgString = ""
            var msg: JSONObject
            do {
                do {
                    input?.mark(8)
                    if (input?.read() ?: 0 == '}'.toInt()) {
                        msgString += "}"
                        break
                    }
                    input?.reset()
                    msgString += input?.readLine()
                } while (true)
                msg = JSONObject(msgString)
                when (msg.optString("type", "")) {
                    "DisplayName" -> if (msg.has("display_name"))
                        callbacks.onDisplayName(msg.getString("display_name"))
                    "BatLogInterval" -> if (msg.has("battery_log_interval_secs"))
                        callbacks.onBatteryLogInterval(
                            msg.getInt("battery_log_interval_secs").toDuration(TimeUnit.SECONDS)
                        )
                    "TransmitAudio" -> if (msg.has("send_audio") && msg.has("recv_audio"))
                        callbacks.onTransmitAudio(
                            msg.getBoolean("send_audio"),
                            msg.getBoolean("recv_audio")
                        )
                    "MuteAudio" -> if (msg.has("send_mute") && msg.has("recv_mute"))
                        callbacks.onMuteAudio(
                            msg.getBoolean("send_mute"),
                            msg.getBoolean("recv_mute")
                        )
                    "AudioStream" -> if (
                        msg.has("recv_audio_port")
                        && msg.has("recv_repair_port")
                        && msg.has("send_audio_port")
                        && msg.has("send_repair_port")
                    )
                        callbacks.onAudioStream(
                            msg.getInt("recv_audio_port"),
                            msg.getInt("recv_repair_port"),
                            msg.getInt("send_audio_port"),
                            msg.getInt("send_repair_port")
                        )
                    else -> {
                    }
                }
            } while (socket?.isConnected == true)

        }
    }
}