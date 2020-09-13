package org.rocstreaming.rocdroid

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.Serializable
import java.util.*
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.ExperimentalTime


class RocStreamService : Service(), CtrlCallback {


    private var mMediaSession: MediaSessionCompat? = null
    private var displayName: String? = null

    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false

    // update changes Stream - garbage collection ok (all 3)
    private var streamData: StreamData = StreamData("192.168.178.43", 0, 0, 10001, 10002)
    private lateinit var recvThread: Thread
    private lateinit var sendThread: Thread
    private var batteryLogThread: Thread? = null
    private lateinit var notificationManagerCompat: NotificationManagerCompat

    // shouldn't change (only if service restarts)
    var ctrlCommunicator: CtrlCommunicator? = null

    private val audioStreaming = AudioStreaming()

    private var initialized = false
    private var controlSearching = false

    // ---------- Android callbacks ----------


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val extras = intent?.extras
        when (action) {
            START -> onServiceStart(true)
            START_NO_SEND -> onServiceStart(false)
            MUTE -> onMuteAudio(extras?.get(MUTE) == true, audioStreaming.deafed, true)
            DEAF -> onMuteAudio(audioStreaming.muted, extras?.get(DEAF) == true, true)
            SEND -> onTransmitAudio(extras?.get(SEND) == true, audioStreaming.receiving, true)
            RECV -> onTransmitAudio(audioStreaming.sending, extras?.get(RECV) == true, true)
            SETTINGS -> onAudioStream(extras?.get(SETTINGS) as StreamData, true)
            CONNECT -> ctrlCommunicator?.searchServer()
            DISCONNECT -> ctrlCommunicator?.stopConnection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sendThread.interrupt()
        recvThread.interrupt()
        notificationManagerCompat.cancel(RECEIVE_NOTIFICATION_ID)
        notificationManagerCompat.cancel(SEND_NOTIFICATION_ID)
        notificationManagerCompat.cancel(CONTROL_NOTIFICATION_ID)
        ctrlCommunicator?.stopConnection()
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        mMediaSession = MediaSessionCompat(this, PREFIX + "SESSION")
    }


    fun onServiceStart(audioAccepted: Boolean) {
        this.permissionToRecordAccepted = audioAccepted
        if (!initialized) {
            initialized = true
            Log.i(CHANNEL_ID, "---------------- service Started ----------------")

            sendThread = Thread()
            recvThread = Thread()

            createNotificationChannel()
            notificationManagerCompat = NotificationManagerCompat.from(this)
            setupNotification()

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
            ctrlCommunicator = CtrlCommunicator(this, userID, this)
        }
        Intent(applicationContext, MainActivity.UIBroadcastReceiver::class.java).apply {
            action = SETTINGS
            putExtra(SETTINGS, streamData)
            sendBroadcast(this)
        }
        Intent(applicationContext, MainActivity.UIBroadcastReceiver::class.java).apply {
            action = TRANSMISSION
            putExtra(TRANSMISSION, getTransmissionState())
            sendBroadcast(this)
        }
    }

    private fun setupNotification() {
        val icDeaf =
            if (audioStreaming.deafed) R.drawable.ic_headset_off_24 else R.drawable.ic_headset_24
        val icMute =
            if (audioStreaming.muted) R.drawable.ic_mic_off_24 else R.drawable.ic_mic_on_24
        val icSend =
            if (audioStreaming.sending) R.drawable.ic_sending_24 else R.drawable.ic_not_sending_24
        val icRecv =
            if (audioStreaming.receiving) R.drawable.ic_receiving_24 else R.drawable.ic_not_receiving_24

        val title = if (ctrlCommunicator?.connected() == true) getString(R.string.connected)
        else if (controlSearching) getString(R.string.connecting)
        else getString(R.string.not_connected)

        val intent =
            Intent(applicationContext, NotificationListener::class.java).apply { action = STOP }
        val notificationDismissedPendingIntent =
            PendingIntent.getBroadcast(
                applicationContext,
                STOP.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(displayName)
            .setSmallIcon(R.drawable.ic_mic_on_24)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setWhen(0L)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setContentIntent(getContentIntent())
            .setOngoing(true)
            .setChannelId(CHANNEL_ID)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1)
                    .setMediaSession(mMediaSession?.sessionToken)
            )
            .setDeleteIntent(notificationDismissedPendingIntent)
            .addAction(icMute, getString(R.string.mute), getIntent(MUTE, !audioStreaming.muted))
            .addAction(icDeaf, getString(R.string.deaf), getIntent(DEAF, !audioStreaming.deafed))
            .addAction(icSend, getString(R.string.send), getIntent(SEND, !audioStreaming.sending))
            .addAction(icRecv, getString(R.string.recv), getIntent(RECV, !audioStreaming.receiving))
        startForeground(27, notification.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            NotificationChannel(
                CHANNEL_ID,
                "RocStream Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableLights(false)
                enableVibration(true)
                vibrationPattern = LongArray(1)
                manager.createNotificationChannel(this)
            }
        }
    }


    private fun getContentIntent(): PendingIntent? {
        val notificationIntent = Intent(applicationContext, MainActivity::class.java)
        return PendingIntent.getActivity(
            applicationContext,
            MainActivity::class.hashCode(),
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }


    private fun getIntent(action: String, bool: Boolean): PendingIntent {
        val intent = Intent(applicationContext, RocStreamService::class.java)
        intent.action = action
        intent.putExtra(action, bool)
        return PendingIntent.getService(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun getIntent(action: String): PendingIntent {
        val intent = Intent(applicationContext, RocStreamService::class.java)
        intent.action = action
        return PendingIntent.getService(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun getTransmissionState(): TransmissionData {
        return TransmissionData(
            sendThread.isAlive,
            recvThread.isAlive,
            audioStreaming.muted,
            audioStreaming.deafed,
            ctrlCommunicator?.connected() == true
        )
    }

    private fun uiUpdate(
        transmissionData: TransmissionData? = null,
        streamData: StreamData? = null,
        controlData: ControlData? = null
    ) {
        transmissionData?.let {
            Intent(applicationContext, MainActivity::class.java).apply {
                action = TRANSMISSION
                putExtra(TRANSMISSION, transmissionData)
                sendBroadcast(this)
            }
        }
        streamData?.let {
            Intent(applicationContext, MainActivity::class.java).apply {
                action = SETTINGS
                putExtra(SETTINGS, streamData)
                sendBroadcast(this)
            }
        }
        controlData?.let {
            Intent(applicationContext, MainActivity::class.java).apply {
                action = CONTROL
                putExtra(CONTROL, controlData)
                sendBroadcast(this)
            }
        }
    }

    // Handles

    private fun onAudioStream(
        streamData: StreamData,
        controlFeedback: Boolean = false
    ) {
        val recvChanged = streamData.recvChanged(streamData)
        val sendChanged = streamData.sendChanged(streamData)
        this.streamData = streamData
        if (recvChanged && audioStreaming.receiving) {
            onTransmitAudio(audioStreaming.sending, false)
            onTransmitAudio(audioStreaming.sending, true)
        }
        if (sendChanged && audioStreaming.sending) {
            onTransmitAudio(false, audioStreaming.receiving)
            onTransmitAudio(true, audioStreaming.receiving)
        }
        uiUpdate(streamData = streamData)
        if (controlFeedback)
            ctrlCommunicator?.sendAudioStream(
                streamData.portAudioRecv,
                streamData.portErrorRecv,
                streamData.portAudioSend,
                streamData.portErrorSend
            )
    }

    fun onMuteAudio(
        sendMute: Boolean = audioStreaming.muted,
        recvMute: Boolean = audioStreaming.deafed,
        controlFeedback: Boolean = false
    ) {
        audioStreaming.muted = sendMute
        audioStreaming.deafed = recvMute
        setupNotification()
        uiUpdate(getTransmissionState())
        if (controlFeedback)
            ctrlCommunicator?.sendMuteAudio(sendMute, recvMute)
    }

    private fun onTransmitAudio(
        sendAudio: Boolean = audioStreaming.sending,
        recvAudio: Boolean = audioStreaming.receiving,
        controlFeedback: Boolean = false
    ) {
        if (!recvAudio && audioStreaming.receiving) {
            audioStreaming.receiving = false
        }
        if (recvAudio && !recvThread.isAlive) {
            audioStreaming.receiving = true
            recvThread = thread(start = true) {
                audioStreaming.startReceiver(
                    streamData.ip,
                    streamData.portAudioRecv,
                    streamData.portErrorRecv
                )
            }
        }
        if (!sendAudio && sendThread.isAlive) {
            audioStreaming.sending = false
        }
        if (sendAudio && !sendThread.isAlive) {
            audioStreaming.sending = true
            sendThread = thread(start = true) {
                audioStreaming.startSender(
                    streamData.ip,
                    streamData.portAudioSend,
                    streamData.portErrorSend
                )
            }
        }
        setupNotification()
        uiUpdate(getTransmissionState())
        if (controlFeedback)
            ctrlCommunicator?.sendTransmitAudio(sendAudio, recvAudio)
    }

// ---------------- Server Callbacks ---------------

    override fun onServerDiscovered(host: String, port: Int) {
        streamData = streamData.modified(host)
        ctrlCommunicator?.connect(host, port)

        controlSearching = false
        setupNotification()

        Intent(INTENT_SEND_UPDATE).let {
            it.action = UPDATE_DISPLAY_NAME
            it.putExtra(UPDATE_IS_CONNECTED, true)
            sendBroadcast(it)
        }

    }

    override fun onDisplayName(displayName: String) {
        this.displayName = displayName
        setupNotification()
        Intent(INTENT_SEND_UPDATE).let {
            it.action = UPDATE_DISPLAY_NAME
            it.putExtra(UPDATE_CONNECTION, displayName)
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
        onAudioStream(streamData)
    }

    override fun onMuteAudio(sendMute: Boolean, recvMute: Boolean) {
        onMuteAudio(
            sendMute = sendMute,
            recvMute = recvMute,
            controlFeedback = false
        )
    }

    override fun onTransmitAudio(sendAudio: Boolean, recvAudio: Boolean) {
        Log.i(CHANNEL_ID, "send: ${sendThread.isAlive}, recv:${recvThread.isAlive}")
        onTransmitAudio(
            sendAudio = sendAudio,
            recvAudio = recvAudio,
            controlFeedback = false
        )
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
            ctrlCommunicator?.sendBatteryLevel(batteryPct ?: 0.0, isCharging)
            Thread.sleep(batterLogInterval.toLongMilliseconds())
        }
    }


}