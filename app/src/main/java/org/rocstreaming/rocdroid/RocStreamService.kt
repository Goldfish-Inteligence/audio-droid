package org.rocstreaming.rocdroid

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.ExperimentalTime


class RocStreamService : Service(), CtrlCallback {

    companion object {
        const val PREFIX = "ROC_DROID_"
        const val CHANNEL_ID = PREFIX + "SERVICE_CHANNEL"
        const val STREAM_DATA_KEY = PREFIX + "STREAM_DATA_KEY"
        const val NOTIFICATION_PREFIX = PREFIX + "NOTIFICATION_"
        const val ACTION_EXTRA = "ACTION_EXTRA"
        const val ACTION_SET_MUTE = "MUTE"
        const val ACTION_SET_DEAF = "DEAF"
        const val ACTION_SET_SEND = "SEND"
        const val ACTION_SET_RECV = "RECEIVE"
        const val ACTION_CONNECT = "CONNECT"
        const val UPDATE_SETTINGS = "UPDATE_SETTINGS"
        const val UID = "USER_ID"
        const val CONTROL_NOTIFICATION_ID = 0x10
        const val SEND_NOTIFICATION_ID = 0x20
        const val RECEIVE_NOTIFICATION_ID = 0x30
    }


    private val binder: IBinder = RocStreamBinder()

    // update changes Stream - garbage collection ok (all 3)
    private lateinit var streamData: StreamData
    private lateinit var recvThread: Thread
    private lateinit var sendThread: Thread
    private var batteryLogThread: Thread? = null
    private lateinit var notificationManagerCompat: NotificationManagerCompat

    // shouldn't change (only if service restarts)
    private lateinit var ctrlCommunicator: CtrlCommunicator

    private val audioStreaming = AudioStreaming()

    private var initialized = false
    private var controlSearching = false

    // ---------- Android callbacks ----------


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) {
            initialized = true
            Log.i(CHANNEL_ID, "---------------- service Started ----------------")
            streamData = intent!!.extras!!.get(STREAM_DATA_KEY) as StreamData

            sendThread = Thread()
            recvThread = Thread()

            createNotificationChannel()
            notificationManagerCompat = NotificationManagerCompat.from(this)
            startForeground(CONTROL_NOTIFICATION_ID, createControlNotification("Connecting", "-"))

            initBR()

            // Setup control communicator
            // generate id
            val sharedPref = getSharedPreferences(UID, Context.MODE_PRIVATE)
            val defaultValue = UUID.randomUUID().toString()
            val userID = sharedPref.getString(UID, defaultValue) ?: defaultValue
            if (userID == defaultValue)
                with(sharedPref.edit()) {
                    putString(UID, userID)
                    commit()
                }
            if (intent.getBooleanExtra(ACTION_CONNECT, false)) {
                ctrlCommunicator = CtrlCommunicator(this, userID, this)
                ctrlCommunicator.searchServer()
            }


        }

        return START_STICKY
    }

    private fun createControlNotification(
        status: String,
        server: String
    ): Notification? {
        return createNotification(
            status,
            text = server,
            action1 = ACTION_SET_RECV,
            action2 = ACTION_SET_SEND,
            requestCodePrefix = CONTROL_NOTIFICATION_ID,
            extra1 = true,
            extra2 = true
        )
    }

    private fun createSendNotification(muted: Boolean): Notification? {
        return createNotification(
            "Sending audio",
            text = if (muted) "Muted" else "Unmuted",
            action1 = if (muted) "UN$ACTION_SET_MUTE" else ACTION_SET_MUTE,
            action2 = ACTION_SET_SEND,
            requestCodePrefix = SEND_NOTIFICATION_ID,
            extra1 = !muted,
            extra2 = false
        )
    }

    private fun createRecvNotification(muted: Boolean): Notification? {
        return createNotification(
            "Receiving audio",
            text = if (muted) "Deafed" else "Listening",
            action1 = if (muted) "UN$ACTION_SET_DEAF" else ACTION_SET_DEAF,
            action2 = ACTION_SET_RECV,
            requestCodePrefix = RECEIVE_NOTIFICATION_ID,
            extra1 = !muted,
            extra2 = false
        )
    }

    private fun createNotification(
        title: String,
        text: String = "",
        action1: String,
        action2: String,
        requestCodePrefix: Int,
        extra1: Boolean,
        extra2: Boolean
    ): Notification? {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 1, notificationIntent, 0)
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)

        builder.addAction(
            0,
            action1,
            PendingIntent.getBroadcast(
                this,
                requestCodePrefix,
                Intent(NOTIFICATION_PREFIX + action1).also {
                    it.putExtra(ACTION_SET_MUTE, extra1)
                },
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        builder.addAction(
            0,
            ACTION_SET_SEND,
            PendingIntent.getBroadcast(
                this,
                requestCodePrefix + 1,
                Intent(NOTIFICATION_PREFIX + action2).also {
                    it.putExtra(ACTION_SET_SEND, extra2)
                },
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        return builder.build()
    }


    private fun initBR() {
        val br = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.action?.let {
                    val action = it.substringAfter(NOTIFICATION_PREFIX)
                    when (action) {
                        ACTION_SET_MUTE -> onMuteAudio(false, recvThread.isAlive)
                        "UN$ACTION_SET_MUTE" -> onMuteAudio(true, recvThread.isAlive)

                        ACTION_SET_DEAF -> onMuteAudio(sendThread.isAlive, false)
                        "UN$ACTION_SET_DEAF" -> onMuteAudio(sendThread.isAlive, true)

                        ACTION_SET_SEND -> onTransmitAudio(
                            intent.getBooleanExtra(ACTION_SET_SEND, sendThread.isAlive),
                            recvThread.isAlive
                        )
                        ACTION_SET_RECV -> onTransmitAudio(
                            sendThread.isAlive,
                            intent.getBooleanExtra(ACTION_SET_SEND, recvThread.isAlive)
                        )
                        UPDATE_SETTINGS ->
                            onAudioStream(intent.getSerializableExtra(UPDATE_SETTINGS) as StreamData)
                        ACTION_CONNECT -> ctrlCommunicator.searchServer()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(NOTIFICATION_PREFIX + ACTION_SET_MUTE)
            addAction(NOTIFICATION_PREFIX + "UN$ACTION_SET_MUTE")
            addAction(NOTIFICATION_PREFIX + ACTION_SET_DEAF)
            addAction(NOTIFICATION_PREFIX + "UN$ACTION_SET_DEAF")
            addAction(NOTIFICATION_PREFIX + ACTION_SET_SEND)
            addAction(NOTIFICATION_PREFIX + ACTION_SET_RECV)
            addAction(NOTIFICATION_PREFIX + UPDATE_SETTINGS)
        }
        registerReceiver(br, filter)
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
            UPDATE_SETTINGS -> {
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

    inner class RocStreamBinder : Binder() {
        fun getService(): RocStreamService = this@RocStreamService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        sendThread.interrupt()
        recvThread.interrupt()
        notificationManagerCompat.cancel(RECEIVE_NOTIFICATION_ID)
        notificationManagerCompat.cancel(SEND_NOTIFICATION_ID)
        notificationManagerCompat.cancel(CONTROL_NOTIFICATION_ID)
        ctrlCommunicator.stopConnection()
        super.onDestroy()
    }


// ---------------- Server Callbacks ---------------

    override fun onServerDiscovered(host: String, port: Int) {
        streamData = streamData.modified(host)
        ctrlCommunicator.connect(host, port)

        controlSearching = false
        startForeground(CONTROL_NOTIFICATION_ID, createControlNotification("Connected", "-"))

        Intent(MainActivity.INTENT_SEND_UPDATE).let {
            it.action = MainActivity.UPDATE_DISPLAY_NAME
            it.putExtra(MainActivity.UPDATE_IS_CONNECTED, true)
            sendBroadcast(it)
        }

    }

    override fun onDisplayName(displayName: String) {
        startForeground(
            CONTROL_NOTIFICATION_ID,
            createControlNotification("Connected", displayName)
        )
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
        if (recvThread.isAlive) startForeground(
            RECEIVE_NOTIFICATION_ID,
            createRecvNotification(recvMute)
        )
        if (sendThread.isAlive) startForeground(
            SEND_NOTIFICATION_ID,
            createRecvNotification(sendMute)
        )
    }

    override fun onTransmitAudio(sendAudio: Boolean, recvAudio: Boolean) {
        Log.i(CHANNEL_ID, "send: ${sendThread.isAlive}, recv:${recvThread.isAlive}")
        if (!recvAudio && recvThread.isAlive) {
            recvThread.interrupt()
            notificationManagerCompat.cancel(RECEIVE_NOTIFICATION_ID)
        }
        if (recvAudio && !recvThread.isAlive) {
            startForeground(RECEIVE_NOTIFICATION_ID, createRecvNotification(audioStreaming.deafed))
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
        if (!sendAudio && sendThread.isAlive) {
            sendThread.interrupt()
            notificationManagerCompat.cancel(SEND_NOTIFICATION_ID)
        }
        if (sendAudio && !sendThread.isAlive) {
            startForeground(SEND_NOTIFICATION_ID, createSendNotification(audioStreaming.muted))
            sendThread = Thread {
                Runnable {
                    audioStreaming.startSender(
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