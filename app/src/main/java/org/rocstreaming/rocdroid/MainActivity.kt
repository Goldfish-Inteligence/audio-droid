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
import java.net.NetworkInterface
import java.security.AccessController.getContext
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

const val SAMPLE_RATE = 44100
const val BUFFER_SIZE = 100

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class MainActivity : AppCompatActivity(), CtrlCallback {


    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val servers: MutableList<String> = MutableList(0, { "" })
    private lateinit var serverAdapter: ArrayAdapter<String>
    private lateinit var ctrlCommunicator: CtrlCommunicator
    private var streamData: StreamData = StreamData("", 0, 0, 0, 0, false, false)

    // We don't want advertising, but yet need IDs
    @SuppressLint("HardwareIds")
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        // Setup control communicator
        val android_id = Settings.Secure.getString(
            this.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        ctrlCommunicator = CtrlCommunicator(this, android_id, this)

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
    fun startSender(@Suppress("UNUSED_PARAMETER") view: View) {
        startStreamUser(view, false)
    }

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    fun startReceiver(view: View) {
        startStreamUser(view, true)
    }

    /**
     * Stop roc sender and audioTrack
     */
    fun stopStream(@Suppress("UNUSED_PARAMETER") view: View) {
        val serviceIntent = Intent(this, RocStreamService::class.java)
        stopService(serviceIntent)
    }

    fun toggleControl(view: View) {
        if (false)//ctrlCommunicator.isConnected
            TODO("ctrl doesn't support turning off connection")
        else
            ctrlCommunicator.searchServer()
    }


    fun saveSettings(view: View) {

        stopStream(view)
        val stream = findViewById<View>(R.id.stream)
        val address = stream.findViewById<Spinner>(R.id.ipEditText).selectedItem as InetAddress
        val audio = stream.findViewById<EditText>(R.id.portAudioEditText).text.toString()
        val error = stream.findViewById<EditText>(R.id.portErrorEditText).text.toString()

        val audioPort = Integer.parseInt(audio)
        val errorPort = Integer.parseInt(error)
    }

    // ---------- View Sync ----------------

    private fun startStreamUser(view: View, receiving: Boolean) {
        stopStream(view)
        val stream = findViewById<View>(R.id.stream)
        val address = stream.findViewById<Spinner>(R.id.ipEditText).selectedItem as InetAddress
        val audio = stream.findViewById<EditText>(R.id.portAudioEditText).text.toString()
        val error = stream.findViewById<EditText>(R.id.portErrorEditText).text.toString()

        val audioPort = Integer.parseInt(audio)
        val errorPort = Integer.parseInt(error)
        val streamData = StreamData(
            address.hostAddress,
            audioPort,
            errorPort,
            audioPort,
            errorPort,
            receiving,
            !receiving
        )
        val intent = Intent(this, RocStreamService::class.java)
        intent.putExtra(RocStreamService.STREAM_DATA_KEY, streamData)
        ContextCompat.startForegroundService(this, intent)
    }

    // ---------------- Server Callbacks ---------------

    override fun onServerDiscovered(host: String, port: Int) {
        streamData = streamData.modified(host)
        ctrlCommunicator.connect(host, port)
        findViewById<Button>(R.id.connectControl).setText(R.string.disconnect_from_control_server)
        findViewById<TextView>(R.id.connectionName).text = "-"
        findViewById<TextView>(R.id.connectionInfo).setText(R.string.connected)
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
            portAudioReceive = recvAudioPort,
            portErrorReceive = recvRepairPort,
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
        TODO("Not yet implemented")
    }

    override fun onTransmitAudio(sendAudio: Boolean, recvAudio: Boolean) {
        TODO("Not yet implemented")
    }

    @ExperimentalTime
    override fun onBatteryLogInterval(batterLogInterval: Duration) {
        TODO("Not yet implemented")
    }

}
