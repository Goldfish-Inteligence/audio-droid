package org.rocstreaming.rocdroid

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.doOnTextChanged


class MainActivity : AppCompatActivity(), uiCallback {

    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false

    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private var isBound = false


    private var mService: RocStreamService? = null
    private var connection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false

        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RocStreamService.RocStreamBinder
            mService = binder.rocStreamService
            isBound = true
            mService?.uiCallback = this@MainActivity
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
        titlebar.findViewById<ToggleButton>(R.id.toggleDeafButton)
            .setOnCheckedChangeListener { _, isChecked -> toggleDeaf(isChecked) }

        val stream = findViewById<View>(R.id.stream)
        val focusChangeListener = object : View.OnFocusChangeListener {
            override fun onFocusChange(v: View?, hasFocus: Boolean) {
                if (!hasFocus) saveSettings()
            }
        }
        stream.findViewById<EditText>(R.id.ipEditText).onFocusChangeListener = focusChangeListener
        stream.findViewById<EditText>(R.id.portAudioEditText).onFocusChangeListener =
            focusChangeListener
        stream.findViewById<EditText>(R.id.portAudioEditTextSend).onFocusChangeListener =
            focusChangeListener
        stream.findViewById<EditText>(R.id.portErrorEditText).onFocusChangeListener =
            focusChangeListener
        stream.findViewById<EditText>(R.id.portErrorEditTextSend).onFocusChangeListener =
            focusChangeListener

        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
    }

    override fun onStart() {
        super.onStart()
        val action = if (permissionToRecordAccepted) START else START_NO_SEND
        Intent(this, RocStreamService::class.java).apply {
            this.action = action
            startForegroundService(this)
        }
        Intent(this, RocStreamService::class.java).apply {
            bindService(this, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound)
            mService?.uiCallback = null
        unbindService(connection)
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

    // --------- UI adaptors ------------

    private fun updateTransmissions(transmission: TransmissionData) {
        val titlebar = findViewById<View>(R.id.titlebar)
        titlebar.findViewById<ToggleButton>(R.id.toggleRecvButton).isChecked = transmission.recv
        titlebar.findViewById<ToggleButton>(R.id.toggleSendButton).isChecked = transmission.send
        titlebar.findViewById<ToggleButton>(R.id.toggleMicButton).isChecked = !transmission.muted
        titlebar.findViewById<ToggleButton>(R.id.toggleDeafButton).isChecked = !transmission.deafed

    }

    private fun updateConnections(streamData: StreamData) {
        val stream = findViewById<View>(R.id.stream)
        stream.findViewById<EditText>(R.id.ipEditText).setText(streamData.ip)
        stream.findViewById<EditText>(R.id.portErrorEditTextSend)
            .setText("${streamData.portErrorSend}")
        stream.findViewById<EditText>(R.id.portAudioEditTextSend)
            .setText("${streamData.portAudioSend}")
        stream.findViewById<EditText>(R.id.portErrorEditText)
            .setText("${streamData.portErrorRecv}")
        stream.findViewById<EditText>(R.id.portAudioEditText)
            .setText("${streamData.portAudioRecv}")

    }

    private fun onDisplayName(control: ControlData) {
        val titlebar = findViewById<View>(R.id.titlebar)
        titlebar.findViewById<TextView>(R.id.connectionName).text = control.serverName

        titlebar.findViewById<TextView>(R.id.connectionInfo)
            .setText(
                when (control.state) {
                    ConnectionState.CONNECTED -> R.string.connected
                    ConnectionState.CONNECTING -> R.string.connecting
                    else -> R.string.not_connected
                }
            )

        findViewById<View>(R.id.control)
            .findViewById<Button>(R.id.connectControl)
            .setText(
                when (control.state) {
                    ConnectionState.CONNECTED -> R.string.disconnect_from_control_server
                    ConnectionState.CONNECTING -> R.string.connecting
                    else -> R.string.connect_to_control_server
                }
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

    private fun toggleDeaf(sending: Boolean) {
        Intent(this, RocStreamService::class.java).apply {
            this.action = DEAF
            putExtra(DEAF, !sending)
            startForegroundService(this)
        }
    }

    fun toggleControl(view: View) {
        val button = view as Button
        if (isBound) {
            if (mService?.ctrlCommunicator?.state == ConnectionState.UNCONNECTED) {
                Intent(this, RocStreamService::class.java).apply {
                    this.action = CONNECT
                    startForegroundService(this)
                }
                button.setText(R.string.connecting)
            } else if (mService?.ctrlCommunicator?.state == ConnectionState.CONNECTED) {
                Intent(this, RocStreamService::class.java).apply {
                    this.action = DISCONNECT
                    startForegroundService(this)
                }
                button.setText(R.string.connect_to_control_server)
            }
        }
    }

    fun saveSettings() {
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

    // Service callback

    override fun onUiUpdate(
        transmissionData: TransmissionData?,
        streamData: StreamData?,
        controlData: ControlData?
    ) {
        transmissionData?.let { updateTransmissions(transmissionData) }
        streamData?.let { updateConnections(streamData) }
        controlData?.let { onDisplayName(controlData) }
    }

    fun stopService(view: View) {
        Intent(this, RocStreamService::class.java).apply {
            this.action = STOP
            startForegroundService(this)
        }
    }

}


