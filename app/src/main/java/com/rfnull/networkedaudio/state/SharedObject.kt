package com.rfnull.networkedaudio.state

import android.media.AudioDeviceInfo
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.locks.ReentrantLock

object SharedObject {
    public enum class RunType {
        LISTENING,
        CONNECTING
    }

    var deviceName: String = ""
    var lock: ReentrantLock = ReentrantLock()
    var audioDevice: AudioDeviceInfo? = null

    var serviceRunning: Boolean = false
    var serviceType: RunType = RunType.LISTENING
}