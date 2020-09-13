package org.rocstreaming.rocdroid

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat


class MainActivity : AppCompatActivity() {

    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private var receiver = UIBroadcastReceiver()

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
        val action = if (permissionToRecordAccepted) START else START_NO_SEND
        Intent(this, RocStreamService::class.java).apply {
            this.action = action
            startForegroundService(this)
        }
    }

    override fun onResume() {
        super.onResume()
        initBroadcastReceiver()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
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

    inner class UIBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SETTINGS -> (intent.getSerializableExtra(SETTINGS) as StreamData?)?.apply {
                    updateConnections(streamData = this)
                }
                TRANSMISSION -> (intent.getSerializableExtra(TRANSMISSION) as TransmissionData?)?.apply {
                    updateConnections(data = this)
                }
                CONTROL -> (intent.getSerializableExtra(CONTROL) as ControlData?)?.apply {
                    onDisplayName(this)
                }
            }
        }
    }

    private fun initBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(TRANSMISSION)
            addAction(CONTROL)
            addAction(SETTINGS)
        }
        registerReceiver(receiver, filter)
    }

    // --------- UI adaptors ------------

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

    private fun onDisplayName(control: ControlData) {
        val titlebar = findViewById<View>(R.id.titlebar)
        titlebar.findViewById<TextView>(R.id.connectionName).text = control.serverName

        titlebar.findViewById<TextView>(R.id.connectionInfo)
            .setText(if (control.contolled) R.string.connected else R.string.not_connected)

        findViewById<View>(R.id.control)
            .findViewById<Button>(R.id.connectControl)
            .setText(
                if (control.contolled) R.string.disconnect_from_control_server
                else R.string.connect_to_control_server
            )
    }

    // --------- User Callbacks ---------

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    private fun toggleSend(send: Boolean) {
        Intent(this, RocStreamService::class.java).apply {
            this.action = SEND
            putExtra(SEND, send)
            startForegroundService(this)
        }
    }

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    private fun toggleRecv(recv: Boolean) {
        Intent(this, RocStreamService::class.java).apply {
            this.action = RECV
            putExtra(RECV, recv)
            startForegroundService(this)
        }
    }


    private fun toggleMic(sending: Boolean) {
        Intent(this, RocStreamService::class.java).apply {
            this.action = MUTE
            putExtra(MUTE, !sending)
            startForegroundService(this)
        }
    }

    fun toggleControl(view: View) {
        val button = view as Button
        if (button.text == resources.getString(R.string.connect_to_control_server)) {
            Intent(this, RocStreamService::class.java).apply {
                this.action = CONNECT
                startForegroundService(this)
            }
            button.setText(R.string.disconnect_from_control_server)
        } else {
            Intent(this, RocStreamService::class.java).apply {
                this.action = DISCONNECT
                startForegroundService(this)
            }
            button.setText(R.string.connect_to_control_server)

        }
    }

    fun saveSettings(view: View) {
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

        val streamData = StreamData(
            ip = address,
            portAudioSend = audioSendPort ?: 0,
            portErrorSend = errorSendPort ?: 0,
            portAudioRecv = audioRecvPort ?: 0,
            portErrorRecv = errorRecvPort ?: 0
        )
        Intent(this, RocStreamService::class.java).apply {
            this.action = SETTINGS
            putExtra(SETTINGS, streamData)
            startForegroundService(this)
        }

    }
}


