package com.rfnull.networkedaudio.activity

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.ActivityCompat
import com.rfnull.networkedaudio.R
import com.rfnull.networkedaudio.service.NetworkingService
import com.rfnull.networkedaudio.state.SharedObject
import android.Manifest

class ListenerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_listener)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)

        val deviceName = intent.getStringExtra("com.rfnull.networkedaudio.deviceName")
        val bitrate = intent.getIntExtra("com.rfnull.networkedaudio.bitrate", 0)

        val intent = Intent(this, NetworkingService::class.java)
        intent.putExtra("com.rfnull.networkedaudio.deviceName", deviceName)
        intent.putExtra("com.rfnull.networkedaudio.bitrate", bitrate)
        startService(intent)
        SharedObject.serviceType = SharedObject.RunType.LISTENING
    }

    override fun onDestroy() {
//        WorkManager.getInstance(this).cancelUniqueWork("discovery")
        val intent = Intent(this, NetworkingService::class.java)
        stopService(intent)
        super.onDestroy()
    }
}
