package org.rocstreaming.rocdroid

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
    private var streamData: StreamData = StreamData("192.168.178.42", 0, 0, 10001, 10002)

    private lateinit var service: RocStreamService
    private var rocBound = false
    private val connection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            rocBound = false
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RocStreamService.RocStreamBinder
            this@MainActivity.service = binder.getService()
            streamData = this@MainActivity.service.onServiceStart(streamData)
            updateConnections(this@MainActivity.service.getTransmissionState())
            rocBound = true
        }

    }

    private fun updateConnections(data: TransmissionData? = null, streamData: StreamData? = null) {
        if (data != null) {
            val titlebar = findViewById<View>(R.id.titlebar)
            titlebar.findViewById<ToggleButton>(R.id.toggleRecvButton).isChecked = data.recv
            titlebar.findViewById<ToggleButton>(R.id.toggleSendButton).isChecked = data.send
            titlebar.findViewById<ToggleButton>(R.id.toggleMicButton).isChecked = !data.muted

            val control = findViewById<View>(R.id.control)
            control.findViewById<Button>(R.id.connectControl).setText(
                if (data.contolled) R.string.disconnect_from_control_server
                else R.string.connect_to_control_server
            )
        }
        if (streamData != null) {
            val stream = findViewById<View>(R.id.stream)
            stream.findViewById<EditText>(R.id.ipEditText).setText(streamData.ip)
            stream.findViewById<EditText>(R.id.portErrorEditTextSend)
                .setText(streamData.portErrorSend)
            stream.findViewById<EditText>(R.id.portAudioEditTextSend)
                .setText(streamData.portAudioSend)
            stream.findViewById<EditText>(R.id.portErrorEditText).setText(streamData.portErrorRecv)
            stream.findViewById<EditText>(R.id.portAudioEditText).setText(streamData.portAudioRecv)

        }
    }


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

    override fun onStart() {
        super.onStart()
        Intent(this, RocStreamService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
            startForegroundService(it)
        }
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        rocBound = false
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
        if (rocBound)
            service.onSendAudio(send)
    }

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    private fun toggleRecv(recv: Boolean) {
        if (rocBound)
            service.onRecvAudio(recv)
    }

    private fun toggleMic(sending: Boolean) {
        if (rocBound)
            service.onMuteMic(!sending)
    }

    fun toggleControl(view: View) {
        if (rocBound) {
            val button = view as Button
            if (button.text.equals(resources.getString(R.string.connect_to_control_server))) {
                service.ctrlCommunicator.searchServer()
                button.setText(R.string.disconnect_from_control_server)
            } else {
                service.ctrlCommunicator.stopConnection()
                button.setText(R.string.connect_to_control_server)
            }
        }
    }


    fun saveSettings(view: View) {
        if (rocBound) {
            val stream = findViewById<View>(R.id.stream)
            val address = stream.findViewById<EditText>(R.id.ipEditText).text.toString()
            val audioRecvPort =
                stream.findViewById<EditText>(R.id.portAudioEditText).text.toString().toIntOrNull()
            val errorRecvPort =
                stream.findViewById<EditText>(R.id.portErrorEditText).text.toString().toIntOrNull()
            val audioSendPort =
                stream.findViewById<EditText>(R.id.portAudioEditTextSend).text.toString()
                    .toIntOrNull()
            val errorSendPort =
                stream.findViewById<EditText>(R.id.portErrorEditTextSend).text.toString()
                    .toIntOrNull()

            service.streamData = StreamData(
                ip = address,
                portAudioSend = audioSendPort ?: 0,
                portErrorSend = errorSendPort ?: 0,
                portAudioRecv = audioRecvPort ?: 0,
                portErrorRecv = errorRecvPort ?: 0
            )
        }
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
                UPDATE_STREAM -> {
                    updateConnections(intent.getSerializableExtra(UPDATE_STREAM) as TransmissionData)
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
            findViewById<View>(R.id.control)
                .findViewById<Button>(R.id.connectControl)
                .setText(R.string.disconnect_from_control_server)

            titlebar.findViewById<ToggleButton>(R.id.toggleRecvButton).isEnabled = true
            titlebar.findViewById<ToggleButton>(R.id.toggleSendButton).isEnabled = true
            titlebar.findViewById<ToggleButton>(R.id.toggleMicButton).isEnabled = true
        } else {
            titlebar.findViewById<TextView>(R.id.connectionInfo).setText(R.string.not_connected)
            findViewById<View>(R.id.control)
                .findViewById<Button>(R.id.connectControl)
                .setText(R.string.connect_to_control_server)

            titlebar.findViewById<ToggleButton>(R.id.toggleRecvButton).isEnabled = false
            titlebar.findViewById<ToggleButton>(R.id.toggleSendButton).isEnabled = false
            titlebar.findViewById<ToggleButton>(R.id.toggleMicButton).isEnabled = false
        }
    }

}
