package org.rocstreaming.rocdroid

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.InetAddress


const val SAMPLE_RATE = 44100
const val BUFFER_SIZE = 100

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class MainActivity : AppCompatActivity() {

    companion object {
        const val UPDATE_CONNECTION = "ACTION_UPDATE_ROC_CONNECTION"
        const val UPDATE_IS_CONNECTED = "ACTION_UPDATE_IS_ROC_CONNECTED"
        const val UPDATE_DISPLAY_NAME = "ACTION_UPDATE_ROC_DISPLAY_NAME"
        const val UPDATE_STREAM = "ACTION_ROC_UPDATE_STREAM"
        const val UPDATE_MUTE = "ACTION_ROC_UPDATE_MUTE"
        const val SEND = "SEND"
        const val RECV = "RECV"
        const val INTENT_SEND_UPDATE = "SEND_ROC_UPDATE"
    }


    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private var streamData: StreamData = StreamData("", 0, 0, 0, 0)

    private var controlConnected = false

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        val titlebar = findViewById<View>(R.id.titlebar)
        titlebar.findViewById<ToggleButton>(R.id.toggleRecvButton)
            .setOnCheckedChangeListener { _, isChecked -> toggleRecv(isChecked) }
        titlebar.findViewById<ToggleButton>(R.id.toggleSendButton)
            .setOnCheckedChangeListener { _, isChecked -> toggleSend(isChecked) }
        titlebar.findViewById<ToggleButton>(R.id.toggleMicButton)
            .setOnCheckedChangeListener { _, isChecked -> toggleMic(isChecked) }

        initBroadcastReceiver()

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


    // --------- User Callbacks ---------

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    private fun toggleSend(send: Boolean) {
        // enure it is running
        startService()
        val intent = Intent(RocStreamService.ROC_STREAM_SERVICE_INTENT_STRING)
        intent.putExtra(RocStreamService.STREAM_SET_FLAG, send)
        intent.action = RocStreamService.ACTION_SET_SEND
        sendBroadcast(intent)
    }

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    private fun toggleRecv(recv: Boolean) {
        // enure it is running
        startService()
        val intent = Intent(RocStreamService.ROC_STREAM_SERVICE_INTENT_STRING)
        intent.putExtra(RocStreamService.STREAM_SET_FLAG, recv)
        intent.action = RocStreamService.ACTION_SET_RECV
        sendBroadcast(intent)

    }

    private fun toggleMic(unmute: Boolean) {
        // enure it is running
        startService()
        val intent = Intent(RocStreamService.ROC_STREAM_SERVICE_INTENT_STRING)
        intent.putExtra(RocStreamService.STREAM_SET_FLAG, unmute)
        intent.action = RocStreamService.ACTION_SET_MUTE
        sendBroadcast(intent)

    }


    fun toggleControl(view: View) {
        if (controlConnected) {
            val serviceIntent = Intent(this, RocStreamService::class.java)
            stopService(serviceIntent)
            TODO("Invoke socket disconnect")
        } else {
            startService()
        }

    }


    fun saveSettings(view: View) {
        val stream = findViewById<View>(R.id.stream)
        val address = stream.findViewById<Spinner>(R.id.ipEditText).selectedItem as InetAddress
        val audioRecvPort =
            Integer.parseInt(stream.findViewById<EditText>(R.id.portAudioEditText).text.toString())
        val errorRecvPort =
            Integer.parseInt(stream.findViewById<EditText>(R.id.portErrorEditText).text.toString())
        val audioSendPort =
            Integer.parseInt(stream.findViewById<EditText>(R.id.portAudioEditTextSend).text.toString())
        val errorSendPort =
            Integer.parseInt(stream.findViewById<EditText>(R.id.portErrorEditTextSend).text.toString())

        val streamData = StreamData(
            ip = address.hostAddress,
            portAudioSend = audioSendPort,
            portErrorSend = errorSendPort,
            portAudioRecv = audioRecvPort,
            portErrorRecv = errorRecvPort
        )
        Intent(RocStreamService.ROC_STREAM_SERVICE_INTENT_STRING).let {
            it.action = RocStreamService.ACTION_UPDATE_STREAM
            it.putExtra(RocStreamService.STREAM_DATA_KEY, streamData)
            sendBroadcast(it)
        }
    }

    private fun startService() {
        val intent = Intent(this, RocStreamService::class.java)
        intent.putExtra(RocStreamService.STREAM_DATA_KEY, streamData)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun initBroadcastReceiver() {
        val br = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                update(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UPDATE_CONNECTION)
            addAction(UPDATE_DISPLAY_NAME)
        }
        registerReceiver(br, filter)
    }

    private fun update(intent: Intent?) {
        intent?.action?.let {
            when (it) {
                UPDATE_DISPLAY_NAME -> {
                    onDisplayName(intent)
                }
                UPDATE_CONNECTION -> {
                    displayChanges(
                        intent.getSerializableExtra(UPDATE_CONNECTION) as StreamData? ?: streamData
                    )
                }
                UPDATE_MUTE -> {
                    updateMute(intent)
                }
                UPDATE_STREAM -> {
                    updateStream(intent)
                }
                else -> {
                }
            }
        }
    }

    private fun updateStream(intent: Intent) {
        findViewById<View>(R.id.titlebar).let {
            if (intent.hasExtra(SEND)) {
                findViewById<ToggleButton>(R.id.toggleSendButton).isChecked =
                    intent.getBooleanExtra(SEND, false)
            }
            if (intent.hasExtra(RECV)) {
                findViewById<ToggleButton>(R.id.toggleRecvButton).isChecked =
                    intent.getBooleanExtra(RECV, false)
            }
        }
    }

    private fun updateMute(intent: Intent) {
        findViewById<View>(R.id.titlebar).let {
            if (intent.hasExtra(SEND)) {
                findViewById<ToggleButton>(R.id.toggleMicButton).isChecked =
                    intent.getBooleanExtra(SEND, false)
            }
            if (intent.hasExtra(RECV)) {
                TODO("No deaf yet")
            }
        }
    }

    private fun displayChanges(streamData: StreamData) {
        findViewById<View>(R.id.stream).apply {
            findViewById<EditText>(R.id.ipEditText).setText(streamData.ip)
            findViewById<EditText>(R.id.portAudioEditText).setText(
                "${streamData.portAudioRecv}",
                TextView.BufferType.EDITABLE
            )
            findViewById<EditText>(R.id.portErrorEditText).setText(
                "${streamData.portErrorRecv}",
                TextView.BufferType.EDITABLE
            )
            findViewById<EditText>(R.id.portAudioEditTextSend).setText(
                "${streamData.portAudioSend}",
                TextView.BufferType.EDITABLE
            )
            findViewById<EditText>(R.id.portErrorEditTextSend).setText(
                "${streamData.portErrorSend}",
                TextView.BufferType.EDITABLE
            )
            findViewById<EditText>(R.id.portAudioEditText).setText(streamData.portAudioRecv)
            findViewById<EditText>(R.id.portErrorEditText).setText(streamData.portErrorRecv)
            findViewById<EditText>(R.id.portAudioEditTextSend).setText(streamData.portAudioSend)
            findViewById<EditText>(R.id.portErrorEditTextSend).setText(streamData.portErrorSend)
        }
    }

    private fun onDisplayName(intent: Intent) {
        val titlebar = findViewById<View>(R.id.titlebar)
        intent.getStringExtra(UPDATE_CONNECTION)?.let {
            titlebar.findViewById<TextView>(R.id.connectionName).text = it
        }
        if (intent.getBooleanExtra(UPDATE_IS_CONNECTED, true)) {
            titlebar.findViewById<TextView>(R.id.connectionInfo).setText(R.string.connected)
            titlebar.findViewById<Button>(R.id.connectControl)
                .setText(R.string.disconnect_from_control_server)

            titlebar.findViewById<ToggleButton>(R.id.toggleRecvButton).isEnabled = true
            titlebar.findViewById<ToggleButton>(R.id.toggleSendButton).isEnabled = true
            titlebar.findViewById<ToggleButton>(R.id.toggleMicButton).isEnabled = true
        } else {
            titlebar.findViewById<TextView>(R.id.connectionInfo).setText(R.string.not_connected)
            titlebar.findViewById<Button>(R.id.connectControl)
                .setText(R.string.connect_to_control_server)

            titlebar.findViewById<ToggleButton>(R.id.toggleRecvButton).isEnabled = false
            titlebar.findViewById<ToggleButton>(R.id.toggleSendButton).isEnabled = false
            titlebar.findViewById<ToggleButton>(R.id.toggleMicButton).isEnabled = false
        }
    }

}
