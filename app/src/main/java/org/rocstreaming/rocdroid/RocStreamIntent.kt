package org.rocstreaming.rocdroid

import android.app.*
import android.content.Intent
import android.media.*
import android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
import android.media.AudioTrack.MODE_STREAM
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.rocstreaming.roctoolkit.*


class RocStreamService : Service() {

    companion object {
        const val CHANNEL_ID = "RocStreamServiceChannel"
        const val STREAM_DATA_KEY = "rocStreamData"
    }

    private lateinit var thread: Thread


    /**
     * Start roc sender, is already in separate thread
     */
    private fun startSender(ip: String, audioPort: Int, errorPort: Int) {

        val audioRecord = createAudioRecord()

        audioRecord.startRecording()
        val config = SenderConfig.Builder(
            SAMPLE_RATE,
            ChannelSet.STEREO,
            FrameEncoding.PCM_FLOAT
        ).build()

        Context().use { context ->
            Sender(context, config).use { sender ->
                sender.bind(Address(Family.AUTO, "0.0.0.0", 0))
                sender.connect(
                    PortType.AUDIO_SOURCE,
                    Protocol.RTP_RS8M_SOURCE,
                    Address(Family.AUTO, ip, audioPort)
                )
                sender.connect(
                    PortType.AUDIO_REPAIR,
                    Protocol.RS8M_REPAIR,
                    Address(Family.AUTO, ip, errorPort)
                )

                val samples = FloatArray(BUFFER_SIZE)
                while (!Thread.currentThread().isInterrupted) {
                    audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    sender.write(samples)
                }
            }
        }

        audioRecord.release()
    }

    /**
     * Start roc receiver, is already in separated thread and play samples via audioTrack
     */
    private fun startReceiver(ip: String, audioPort: Int, errorPort: Int) {

        val audioTrack = createAudioTrack()

        audioTrack.play()
        val config = ReceiverConfig.Builder(
            SAMPLE_RATE,
            ChannelSet.STEREO,
            FrameEncoding.PCM_FLOAT
        ).build()

        Context().use { context ->
            Receiver(context, config).use { receiver ->
                receiver.bind(
                    PortType.AUDIO_SOURCE,
                    Protocol.RTP_RS8M_SOURCE,
                    Address(Family.AUTO, ip, audioPort)
                )
                receiver.bind(
                    PortType.AUDIO_REPAIR,
                    Protocol.RS8M_REPAIR,
                    Address(Family.AUTO, ip, errorPort)
                )

                val samples = FloatArray(BUFFER_SIZE)
                while (!Thread.currentThread().isInterrupted) {
                    receiver.read(samples)
                    audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                }
            }
        }

        audioTrack.release()
    }


    private fun createAudioRecord(): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO) // should be mono
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding
        )

        return AudioRecord(
            // https://developer.android.com/reference/kotlin/android/media/MediaRecorder.AudioSource#voice_performance
            // unsure if chosen correctly
            @Suppress
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding,
            bufferSize
        )
    }

    private fun createAudioTrack(): AudioTrack {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setFlags(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val bufferSize = AudioTrack.getMinBufferSize(
            audioFormat.sampleRate,
            audioFormat.encoding,
            audioFormat.channelMask
        )

        return AudioTrack(
            audioAttributes,
            audioFormat,
            bufferSize,
            MODE_STREAM,
            AUDIO_SESSION_ID_GENERATE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val streamData: StreamData = intent!!.extras!!.get(STREAM_DATA_KEY) as StreamData

        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RocStream Service")
            .setContentText(streamData.ip)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(1, notification)

        thread = Thread {
            Runnable {
                if (streamData.receiving)
                    startReceiver(streamData.ip, streamData.portAudio, streamData.portError)
                else
                    startSender(streamData.ip, streamData.portAudio, streamData.portError)
            }
        }
        thread.start()

        return START_REDELIVER_INTENT
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
        thread.interrupt()
        super.onDestroy()
    }

}