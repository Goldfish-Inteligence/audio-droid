package org.rocstreaming.rocdroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.ExperimentalTime


const val SAMPLE_RATE = 44100
const val BUFFER_SIZE = 100

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class MainActivity : AppCompatActivity(), CtrlCallback {


    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private lateinit var ctrlCommunicator: CtrlCommunicator
    private var streamData: StreamData = StreamData("", 0, 0, 0, 0)
    private var controlConnected = false
    private var controlSearching = false

    // We don't want advertising, but yet need IDs
    @SuppressLint("HardwareIds")
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

        // Setup control communicator
        val androidID = Settings.Secure.getString(
            this.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        ctrlCommunicator = CtrlCommunicator(this, androidID, this)


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
        intent.putExtra(RocStreamService.STREAM_TOGGLE_KEY, send)
        intent.action = RocStreamService.ACTION_TOGGLE_SEND
        sendBroadcast(intent)
    }

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    private fun toggleRecv(recv: Boolean) {
        // enure it is running
        startService()
        val intent = Intent(RocStreamService.ROC_STREAM_SERVICE_INTENT_STRING)
        intent.putExtra(RocStreamService.STREAM_TOGGLE_KEY, recv)
        intent.action = RocStreamService.ACTION_TOGGLE_RECV
        sendBroadcast(intent)

    }

    private fun toggleMic(unmute: Boolean) {
        // enure it is running
        startService()
        val intent = Intent(RocStreamService.ROC_STREAM_SERVICE_INTENT_STRING)
        intent.putExtra(RocStreamService.STREAM_TOGGLE_KEY, unmute)
        intent.action = RocStreamService.ACTION_TOGGLE_MUTE
        sendBroadcast(intent)

    }


    fun toggleControl(view: View) {
        if (controlConnected) {
            val serviceIntent = Intent(this, RocStreamService::class.java)
            stopService(serviceIntent)
            TODO("Invoke socket disconnect")
        } else if (!controlSearching) {
            ctrlCommunicator.searchServer()
            controlSearching = true
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

        streamData = StreamData(
            ip = address.hostAddress,
            portAudioSend = audioSendPort,
            portErrorSend = errorSendPort,
            portAudioRecv = audioRecvPort,
            portErrorRecv = errorRecvPort
        )
    }

// ---------------- Server Callbacks ---------------

    override fun onServerDiscovered(host: String, port: Int) {
        streamData = streamData.modified(host)
        ctrlCommunicator.connect(host, port)
        findViewById<Button>(R.id.connectControl).setText(R.string.disconnect_from_control_server)
        findViewById<TextView>(R.id.connectionName).text = "-"
        findViewById<TextView>(R.id.connectionInfo).setText(R.string.connected)

        startService()
        controlConnected = true
        controlSearching = false

        val titlebar = findViewById<View>(R.id.titlebar)
        titlebar.findViewById<ToggleButton>(R.id.toggleRecvButton).isEnabled = true
        titlebar.findViewById<ToggleButton>(R.id.toggleSendButton).isEnabled = true
        titlebar.findViewById<ToggleButton>(R.id.toggleMicButton).isEnabled = true
    }

    private fun startService() {
        val intent = Intent(this, RocStreamService::class.java)
        intent.putExtra(RocStreamService.STREAM_DATA_KEY, streamData)
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onDisplayName(displayName: String) {
        findViewById<TextView>(R.id.connectionName).text = displayName
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
        findViewById<EditText>(R.id.portAudioEditText).setText(
            "$recvAudioPort",
            TextView.BufferType.EDITABLE
        )
        findViewById<EditText>(R.id.portErrorEditText).setText(
            "$recvRepairPort",
            TextView.BufferType.EDITABLE
        )
        findViewById<EditText>(R.id.portAudioEditTextSend).setText(
            "$sendAudioPort",
            TextView.BufferType.EDITABLE
        )
        findViewById<EditText>(R.id.portErrorEditTextSend).setText(
            "$sendRepairPort",
            TextView.BufferType.EDITABLE
        )

    }

    override fun onMuteAudio(sendMute: Boolean, recvMute: Boolean) {
        toggleMic(sendMute)
        TODO("recvMute Implementation")
    }

    override fun onTransmitAudio(sendAudio: Boolean, recvAudio: Boolean) {
        toggleRecv(recvAudio)
        toggleSend(sendAudio)
    }

    @ExperimentalTime
    override fun onBatteryLogInterval(batterLogInterval: Duration) {
        TODO("Not yet implemented")
    }

}
