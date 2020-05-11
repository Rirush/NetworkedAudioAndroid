package com.rfnull.networkedaudio.worker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.rfnull.networkedaudio.state.SharedObject
import org.libsodium.jni.crypto.Random
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.withLock

class DiscoveryWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    lateinit var socket: MulticastSocket

    override fun doWork(): Result {
        socket = MulticastSocket(11111)
        socket.joinGroup(InetAddress.getByName("239.101.13.37"))

        val id = Random().randomBytes(8)

        while (true) {
            val buf = ByteArray(3)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            if (packet.data!!.contentEquals("Adv".toByteArray())) {
                var result = ByteArray(0)
                Log.d("Multicast", "Got multicasted")
                SharedObject.lock.withLock {
                    result = "Hey".toByteArray() + id + SharedObject.deviceName.toByteArray(charset("UTF-8"))
                }
                val responsePacket = DatagramPacket(result, result.size, packet.address, packet.port)
                socket.send(responsePacket)
            }
        }

        return Result.success()
    }

    override fun onStopped() {
        socket.close()
        super.onStopped()
    }
}