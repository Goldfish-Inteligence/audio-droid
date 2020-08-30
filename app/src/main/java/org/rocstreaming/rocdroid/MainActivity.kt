package org.rocstreaming.rocdroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import org.rocstreaming.rocdroid.databinding.AlternateMainBinding
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

const val SAMPLE_RATE = 44100
const val BUFFER_SIZE = 100

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class MainActivity : AppCompatActivity() {


    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private var servers: List<String> = emptyList()
    private lateinit var serverAdapter: ArrayAdapter<String>

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataBindingUtil.setContentView(this, R.layout.alternate_main) as AlternateMainBinding

        // Fill spinner values
        servers = getIpAddresses().map { it.hostAddress }
        serverAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, servers)
        findViewById<View>(R.id.receiver).findViewById<Spinner>(R.id.ipEditText).adapter = serverAdapter

        // Map start/stop calls
        ConnectionType.SENDER.start = ::startSender
        ConnectionType.SENDER.stop = ::stopStream
        ConnectionType.RECEIVER.start = ::startReceiver
        ConnectionType.RECEIVER.stop = ::startReceiver

        //initialize audio
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { audioDeviceInfo -> audioDeviceInfo.toString() }

        // audio outputs
        val spinner: Spinner = findViewById<View>(R.id.sender).findViewById(R.id.spinner)
        val adapter =
            ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, outputs)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter


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
     * Start roc sender in separated thread and play samples via audioTrack
     */
    fun startSender(@Suppress("UNUSED_PARAMETER") view: View) {
        stopStream(view)
        val sender = findViewById<View>(R.id.sender)
        startStream(sender, false)
    }

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    fun startReceiver(view: View) {
        stopStream(view)
        val receiver = findViewById<View>(R.id.receiver)
        startStream(receiver, true)
    }

    private fun startStream(receiver: View, receiving: Boolean) {
        val address = receiver.findViewById<Spinner>(R.id.ipEditText).selectedItem as InetAddress
        val audioPort = receiver.findViewById<EditText>(R.id.portAudioEditText).text.toString()
        val errorPort = receiver.findViewById<EditText>(R.id.portErrorEditText).text.toString()

        val intent = Intent(this, RocStreamService::class.java)
        val streamData = StreamData(
            address.hostAddress,
            Integer.parseInt(audioPort),
            Integer.parseInt(errorPort),
            receiving
        )
        intent.putExtra(RocStreamService.STREAM_DATA_KEY, streamData)
        ContextCompat.startForegroundService(this, intent)
    }

    /**
     * Stop roc sender and audioTrack
     */
    fun stopStream(@Suppress("UNUSED_PARAMETER") view: View) {
        val serviceIntent = Intent(this, RocStreamService::class.java)
        stopService(serviceIntent)
    }


    private fun getIpAddresses(): List<InetAddress> {
        try {
            Log.i(
                "rocDroid-list",
                NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .filter { !it.isLoopbackAddress && !it.hostAddress.contains(':') }
                    .joinToString("\n") { it.hostAddress })
            return NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && !it.hostAddress.contains(':') }
        } catch (ignored: Exception) {
        }
        return emptyList()
    }

}
