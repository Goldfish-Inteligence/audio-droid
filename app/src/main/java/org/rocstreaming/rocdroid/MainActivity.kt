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
import androidx.databinding.DataBindingUtil
import org.rocstreaming.rocdroid.databinding.AlternateMainBinding
import java.net.NetworkInterface

const val SAMPLE_RATE = 44100
const val BUFFER_SIZE = 100

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

class MainActivity : AppCompatActivity() {

    private var serviceIntent: Intent? = null

    // Requesting permission to RECORD_AUDIO
    private var permissionToRecordAccepted = false
    private var permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataBindingUtil.setContentView(this, R.layout.alternate_main) as AlternateMainBinding
        ConnectionType.SENDER.start = ::startSender
        ConnectionType.SENDER.stop = ::stopConnection
        ConnectionType.RECEIVER.start = ::startReceiver
        ConnectionType.RECEIVER.stop = ::startReceiver

        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { audioDeviceInfo -> audioDeviceInfo.toString() }
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
        val sender = findViewById<View>(R.id.sender)

        val ip = sender.findViewById<EditText>(R.id.ipEditText).text.toString()
        val audioPort = sender.findViewById<EditText>(R.id.portAudioEditText).text.toString()
        val errorPort = sender.findViewById<EditText>(R.id.portErrorEditText).text.toString()
        val intent = Intent(this, RocStreamService::class.java)
        intent.putExtra(RocStreamService.KEY_IP, ip)
        try {
            intent.putExtra(RocStreamService.KEY_AUDIO_PORT, audioPort.toInt())
        } catch (e: NumberFormatException) {
        }
        try {
            intent.putExtra(RocStreamService.KEY_ERROR_PORT, errorPort.toInt())
        } catch (e: NumberFormatException) {
        }
        intent.putExtra(RocStreamService.KEY_RECEIVING, false)
        startService(intent)
        serviceIntent = intent

    }

    /**
     * Start roc sender in separated thread and play samples via audioTrack
     */
    fun startReceiver(@Suppress("UNUSED_PARAMETER") view: View) {

        val sender = findViewById<View>(R.id.receiver)

        val ip = sender.findViewById<EditText>(R.id.ipEditText).text.toString()
        val audioPort = sender.findViewById<EditText>(R.id.portAudioEditText).text.toString()
        val errorPort = sender.findViewById<EditText>(R.id.portErrorEditText).text.toString()
        val intent = Intent(this, RocStreamService::class.java)
        intent.putExtra(RocStreamService.KEY_IP, ip)
        try {
            intent.putExtra(RocStreamService.KEY_AUDIO_PORT, audioPort.toInt())
        } catch (e: NumberFormatException) {
        }
        try {
            intent.putExtra(RocStreamService.KEY_ERROR_PORT, errorPort.toInt())
        } catch (e: NumberFormatException) {
        }
        intent.putExtra(RocStreamService.KEY_RECEIVING, true)
        Log.v("roc-droid",ip)
        startService(intent)
        serviceIntent = intent

    }

    /**
     * Stop roc sender and audioTrack
     */
    fun stopConnection(@Suppress("UNUSED_PARAMETER") view: View) {
        stopService(serviceIntent)
    }

    // TODO: do we need this?
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
