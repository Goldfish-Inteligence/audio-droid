package org.rocstreaming.rocdroid

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.ExperimentalTime


class RocStreamService : Service(), CtrlCallback {

    companion object {
        const val CHANNEL_ID = "RocStreamServiceChannel"
        const val STREAM_DATA_KEY = "STREAM_DATA_KEY"
        const val STREAM_SET_FLAG = "STREAM_TOGGLE_KEY"
        const val ROC_STREAM_SERVICE_INTENT_STRING = "ROC_STREAM_SERVICE_UPDATE"
        const val ACTION_SET_MUTE = "ACTION_TOGGLE_MUTE"
        const val ACTION_SET_DEAF = "ACTION_TOGGLE_DEAF"
        const val ACTION_SET_SEND = "ACTION_TOGGLE_SEND"
        const val ACTION_SET_RECV = "ACTION_TOGGLE_RECEIVE"
        const val ACTION_UPDATE_STREAM = "ACTION_UPDATE_STREAM"
    }

    // update changes Stream - garbage collection ok (all 3)
    private lateinit var streamData: StreamData
    private lateinit var recvThread: Thread
    private lateinit var sendThread: Thread
    private var batteryLogThread: Thread? = null

    // shouldn't change (only if service restarts)
    private lateinit var ctrlCommunicator: CtrlCommunicator

    private val audioStreaming = AudioStreaming()

    private var initialized = false
    private var controlSearching = false

    // ---------- Android callbacks ----------


    // We don't want advertising, but yet need IDs
    @SuppressLint("HardwareIds")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) {
            Log.i(CHANNEL_ID, "Service Start invoked!")
            streamData = intent!!.extras!!.get(STREAM_DATA_KEY) as StreamData

            createNotificationChannel()
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

            val notificationLayout =
                RemoteViews(applicationContext.packageName, R.layout.notification)

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setCustomContentView(notificationLayout)
                .setFullScreenIntent(pendingIntent, true)
                .build()
            startForeground(1, notification)
            initialized = true
            val br = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    intent?.action?.let {
                        if (intent.hasExtra(STREAM_SET_FLAG))
                            onAction(
                                it,
                                applies = intent.getBooleanExtra(STREAM_SET_FLAG, false)
                            )
                        if (intent.hasExtra(STREAM_DATA_KEY))
                            onAction(
                                it,
                                data = intent.getSerializableExtra(STREAM_DATA_KEY)!! as StreamData
                            )
                    }
                }
            }
            val filter = IntentFilter(ROC_STREAM_SERVICE_INTENT_STRING).apply {
                addAction(ACTION_SET_MUTE)
                addAction(ACTION_SET_SEND)
                addAction(ACTION_SET_RECV)
                addAction(ACTION_UPDATE_STREAM)
            }
            registerReceiver(br, filter)

            // Setup control communicator
            val androidID = Settings.Secure.getString(
                this.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            ctrlCommunicator = CtrlCommunicator(this, androidID, this)

            sendThread = Thread()
            recvThread = Thread()
        }

        return START_STICKY
    }


    private fun onAction(action: String, data: StreamData? = null, applies: Boolean = false) {
        when (action) {
            ACTION_SET_RECV -> {
                onTransmitAudio(sendThread.isAlive, applies)
            }
            ACTION_SET_SEND -> {
                onTransmitAudio(applies, recvThread.isAlive)
            }
            // actions during streaming - no locals should be used here
            ACTION_SET_MUTE -> {
                audioStreaming.muted = applies
                onMuteAudio(audioStreaming.muted, audioStreaming.deafed)
            }
            ACTION_SET_DEAF -> {
                audioStreaming.deafed = applies
                onMuteAudio(audioStreaming.muted, audioStreaming.deafed)
            }
            ACTION_UPDATE_STREAM -> {
                data?.let { onAudioStream(streamData) }
            }
            else -> {
                return // why are we here?
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "RocStream Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(
                NotificationManager::class.java
            )
            manager.createNotificationChannel(serviceChannel)
        }
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        sendThread.interrupt()
        super.onDestroy()
    }


// ---------------- Server Callbacks ---------------

    override fun onServerDiscovered(host: String, port: Int) {
        streamData = streamData.modified(host)
        ctrlCommunicator.connect(host, port)

        controlSearching = false

        Intent(MainActivity.INTENT_SEND_UPDATE).let {
            it.action = MainActivity.UPDATE_DISPLAY_NAME
            it.putExtra(MainActivity.UPDATE_IS_CONNECTED, true)
            sendBroadcast(it)
        }

    }

    override fun onDisplayName(displayName: String) {
        Intent(MainActivity.INTENT_SEND_UPDATE).let {
            it.action = MainActivity.UPDATE_DISPLAY_NAME
            it.putExtra(MainActivity.UPDATE_CONNECTION, displayName)
            sendBroadcast(it)
        }
    }

    override fun onAudioStream(
        recvAudioPort: Int,
        recvRepairPort: Int,
        sendAudioPort: Int,
        sendRepairPort: Int
    ) {
        streamData = streamData.modified(
            portAudioRecv = recvAudioPort,
            portErrorRecv = recvRepairPort,
            portAudioSend = sendAudioPort,
            portErrorSend = sendRepairPort
        )
        onAudioStream(streamData, true)
    }

    private fun onAudioStream(streamData: StreamData, notify: Boolean = false) {
        val recvChanged = streamData.recvChanged(streamData)
        val sendChanged = streamData.sendChanged(streamData)
        this.streamData = streamData
        if (recvChanged && recvThread.isAlive) {
            recvThread.interrupt()
            onAction(ACTION_SET_RECV, applies = true)
        }
        if (sendChanged && sendThread.isAlive) {
            sendThread.interrupt()
            onAction(ACTION_SET_SEND, applies = true)
        }
        if (notify)
            Intent(MainActivity.INTENT_SEND_UPDATE).let {
                it.action = MainActivity.UPDATE_CONNECTION
                it.putExtra(MainActivity.UPDATE_CONNECTION, streamData)
                sendBroadcast(it)
            }
    }

    override fun onMuteAudio(sendMute: Boolean, recvMute: Boolean) {
        audioStreaming.muted = sendMute
        audioStreaming.deafed = recvMute
    }

    override fun onTransmitAudio(sendAudio: Boolean, recvAudio: Boolean) {
        if (!recvAudio && recvThread.isAlive) recvThread.interrupt()
        if (recvAudio && !recvThread.isAlive) {
            recvThread = Thread {
                Runnable {
                    audioStreaming.startReceiver(
                        streamData.ip,
                        streamData.portAudioRecv,
                        streamData.portErrorRecv
                    )
                }
            }.apply { start() }
        }
        if (!sendAudio && sendThread.isAlive) sendThread.interrupt()
        if (sendAudio && !sendThread.isAlive) {
            sendThread = Thread {
                Runnable {
                    audioStreaming.startReceiver(
                        streamData.ip,
                        streamData.portAudioSend,
                        streamData.portErrorSend
                    )
                }
            }.apply { start() }
        }
    }

    @ExperimentalTime
    override fun onBatteryLogInterval(batterLogInterval: Duration) {
        batteryLogThread?.interrupt()
        batteryLogThread = thread {
            // This might cause garbage collection, lets hope not
            val batteryStatus: Intent? =
                IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                    registerReceiver(null, ifilter)
                }
            val batteryPct: Double? = batteryStatus?.let { intent ->
                val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                level.toDouble() / scale.toDouble()
            }
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL
            ctrlCommunicator.sendBatteryLevel(batteryPct ?: 0.0, isCharging)
            Thread.sleep(batterLogInterval.toLongMilliseconds())
        }
    }


}