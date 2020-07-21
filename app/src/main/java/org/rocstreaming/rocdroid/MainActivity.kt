package org.rocstreaming.rocdroid

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioFormat.*
import android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
import android.media.AudioRecord.READ_BLOCKING
import android.media.AudioTrack.MODE_STREAM
import android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
import android.media.MediaRecorder.AudioSource.VOICE_PERFORMANCE
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.rocstreaming.roctoolkit.*
import java.net.NetworkInterface

const val SAMPLE_RATE = 44100
const val BUFFER_SIZE = 100

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class MainActivity : AppCompatActivity() {

    private var thread: Thread? = null
    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val ipTextView: TextView = findViewById(R.id.ipTextView)
        ipTextView.text = getIpAddresses()

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (!permissionToRecordAccepted) finish()
    }

    /**
     * Start roc receiver in separated thread and play samples via audioTrack
     */
    fun startReceiver(@Suppress("UNUSED_PARAMETER") view: View) {
        if (thread?.isAlive == true) {
            return
        }

        thread = Thread(Runnable {
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
                        Address(Family.AUTO, "100.124.83.113", 10001)
                    )
                    sender.connect(
                        PortType.AUDIO_REPAIR,
                        Protocol.RS8M_REPAIR,
                        Address(Family.AUTO, "100.124.83.113", 10002)
                    )

                    val samples = FloatArray(BUFFER_SIZE)
                    while (!Thread.currentThread().isInterrupted) {
                        audioRecord.read(samples, 0, samples.size, READ_BLOCKING)
                        sender.write(samples)
                    }
                }
            }

            audioRecord.release()
        })

        thread!!.start()
    }

    /**
     * Stop roc receiver and audioTrack
     */
    fun stopReceiver(@Suppress("UNUSED_PARAMETER") view: View) {
        thread?.interrupt()
    }

    private fun createAudioRecord(): AudioRecord {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(USAGE_MEDIA)
            .setFlags(PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(ENCODING_PCM_FLOAT)
            .setChannelMask(CHANNEL_OUT_STEREO) // should be mono
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding
        )

        return AudioRecord(
            // https://developer.android.com/reference/kotlin/android/media/MediaRecorder.AudioSource#voice_performance
            // unsure if choiced correctly
            VOICE_PERFORMANCE,
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding,
            bufferSize
        )
    }

    private fun getIpAddresses(): String {
        try {
            return NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && !it.hostAddress.contains(':') }
                .joinToString("\n") { it.hostAddress }
        } catch (ignored: Exception) {
        }
        return ""
    }
}
