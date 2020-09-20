package org.rocstreaming.rocdroid

import android.content.Intent
import android.media.*
import org.rocstreaming.roctoolkit.*


class AudioStreaming {

    var muted = false
    var deafed = false
    var sending = false
    var receiving = false

    /**
     * Start roc sender, is already in separate thread
     */
    fun startSender(ip: String, audioPort: Int, errorPort: Int) {

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
                val silence = FloatArray(BUFFER_SIZE) { 0f }
                while (!Thread.currentThread().isInterrupted && sending) {
                    audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (muted)
                        sender.write(silence)
                    else
                        sender.write(samples)
                }
            }
        }

        audioRecord.release()
    }

    /**
     * Start roc receiver, is already in separated thread and play samples via audioTrack
     */
    fun startReceiver(ip: String, audioPort: Int, errorPort: Int) {

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
                    Address(Family.AUTO, "0.0.0.0", audioPort)
                )
                receiver.bind(
                    PortType.AUDIO_REPAIR,
                    Protocol.RS8M_REPAIR,
                    Address(Family.AUTO, "0.0.0.0", errorPort)
                )

                val samples = FloatArray(BUFFER_SIZE)
                while (!Thread.currentThread().isInterrupted && receiving) {
                    if (!deafed) {
                        receiver.read(samples)
                        audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    }
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
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }
}


