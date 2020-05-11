package com.rfnull.networkedaudio.activity.event

import android.media.AudioDeviceInfo
import com.rfnull.networkedaudio.activity.MainActivity
import android.media.AudioDeviceCallback as Callback;

class AudioDeviceCallback(var activity: MainActivity) : Callback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
        activity.enumerateDevices()
        super.onAudioDevicesAdded(addedDevices)
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
        activity.enumerateDevices()
        super.onAudioDevicesRemoved(removedDevices)
    }
}