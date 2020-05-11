package com.rfnull.networkedaudio.activity

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.work.*
import com.jaredrummler.android.device.DeviceName
import com.rfnull.networkedaudio.activity.event.AudioDeviceCallback
import com.rfnull.networkedaudio.R
import com.rfnull.networkedaudio.activity.event.TextWatcher
import com.rfnull.networkedaudio.state.SharedObject
import kotlinx.android.synthetic.main.activity_main.*
import org.libsodium.jni.NaCl
import org.libsodium.jni.keys.KeyPair
import com.score.rahasak.utils.OpusEncoder

class MainActivity : AppCompatActivity() {
    lateinit var audioManager: AudioManager

    lateinit var audioDeviceCallback: AudioDeviceCallback

    lateinit var latestDevices: MutableList<AudioDeviceInfo>
    lateinit var keyPair: KeyPair

    fun enumerateDevices() {
        val temp: MutableList<String> = mutableListOf(getString(R.string.disabled))
        latestDevices = mutableListOf()
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach {
            if (it.isSource && it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_FM_TUNER) {
                latestDevices.add(it)
                if (it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    temp.add(getString(R.string.built_in_mic))
                else
                    temp.add(it.productName.toString())
            }
        }
        devicesSpinner.adapter = ArrayAdapter(this,
            R.layout.support_simple_spinner_dropdown_item, temp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize sodium globally
        NaCl.sodium()

        // Setup audio manager
        latestDevices = mutableListOf()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioDeviceCallback =
            AudioDeviceCallback(this)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler())

        val pref = getPreferences(Context.MODE_PRIVATE)
        DeviceName.init(this)
        val name = pref.getString("deviceName", DeviceName.getDeviceName())
        // Get device name
        deviceNameEdit.setText(name)
        SharedObject.deviceName = name!!

        deviceNameEdit.addTextChangedListener(TextWatcher())

        val bitrate = pref.getInt("bitrate", 0)
        if (bitrate in 6..510) {
            bitrateEdit.setText(bitrate.toString())
        }
        // Start multicast listener
        /*val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<MulticastListener>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("multicast", ExistingWorkPolicy.REPLACE, request)*/
    }

    fun saveSettings() {
        val pref = getPreferences(Context.MODE_PRIVATE)
        pref.edit {
            if (SharedObject.deviceName != "")
                this.putString("deviceName", SharedObject.deviceName)
            else
                this.remove("deviceName")

            if (bitrateEdit.text.toString() != "")
                this.putInt("bitrate", Integer.parseInt(bitrateEdit.text.toString()))
            else
                this.remove("bitrate")
        }
    }

    override fun onDestroy() {
        saveSettings()
        super.onDestroy()
    }

    fun startListening(view: View) {
        if (bitrateEdit.text.toString() != "" &&
            Integer.parseInt(bitrateEdit.text.toString()) >= 6 &&
            Integer.parseInt(bitrateEdit.text.toString()) <= 510) {
            val intent = Intent(this, ListenerActivity::class.java).apply {
                if (SharedObject.deviceName == "") {
                    deviceNameEdit.setText(DeviceName.getDeviceName())
                }
                putExtra("com.rfnull.networkedaudio.deviceName", deviceNameEdit.text.toString())
                putExtra("com.rfnull.networkedaudio.bitrate", Integer.parseInt(bitrateEdit.text.toString()))
            }
            saveSettings()
            if (devicesSpinner.selectedItemPosition > 0)
                SharedObject.audioDevice = latestDevices[devicesSpinner.selectedItemPosition - 1]
            startActivity(intent)
        } else {
            Toast.makeText(this, "Invalid bitrate!", Toast.LENGTH_LONG).show()
        }
    }
}
