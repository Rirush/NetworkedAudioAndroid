package com.rfnull.networkedaudio.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rfnull.networkedaudio.R
import com.rfnull.networkedaudio.activity.ListenerActivity
import com.rfnull.networkedaudio.activity.MainActivity
import com.rfnull.networkedaudio.state.SharedObject
import com.score.rahasak.utils.OpusDecoder
import com.score.rahasak.utils.OpusEncoder
import org.libsodium.jni.Sodium
import org.libsodium.jni.SodiumConstants
import org.libsodium.jni.crypto.Random
import org.libsodium.jni.keys.KeyPair
import java.net.*
import java.nio.ByteBuffer

class DiscoveryThread(private val deviceName: String) : Thread() {
    var socket: MulticastSocket? = null

    override fun run() {
        val id = Random().randomBytes(8)
        socket = MulticastSocket(11111)
        socket?.joinGroup(InetAddress.getByName("239.101.13.37"))
        Log.d("DiscoveryThread", "discovery task started")
        try {
            while (true) {
                val buf = ByteArray(3)
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet)
                if (packet.data!!.contentEquals("Adv".toByteArray())) {
                    val result: ByteArray = "Hey".toByteArray() + id + deviceName.toByteArray(charset("UTF-8"))
                    val responsePacket = DatagramPacket(result, result.size, packet.address, packet.port)
                    Log.d("DiscoveryThread", "advertising itself")
                    socket?.send(responsePacket)
                }
            }
        } catch (e: SocketException) {
            Log.d("DiscoveryThread", "socket is closed")
            return
        }
    }

    override fun interrupt() {
        socket?.close()
        super.interrupt()
    }
}

class AudioRecorderThread(private val sharedSecret: ByteArray?, private val sessionAddress: InetAddress?, private val sessionID: ByteArray, private val bitrate: Int) : Thread() {
    var socket: DatagramSocket? = null
    var audioRecord: AudioRecord? = null
    var encoder: OpusEncoder? = null
    var seq: Long = 0

    fun longToBytes(long: Long): ByteArray {
        ByteBuffer.allocate(Long.SIZE_BYTES).apply {
            putLong(long)
            return array()
        }
    }

    override fun run() {
        socket = DatagramSocket()
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build())
            .build()
        encoder = OpusEncoder()
        encoder?.init(48000, 2, OpusEncoder.OPUS_APPLICATION_AUDIO)
        encoder?.setBitrate(bitrate * 1000)
        encoder?.setComplexity(5)
        audioRecord?.preferredDevice = SharedObject.audioDevice
        audioRecord?.startRecording()
        try {
            while (true) {
                val samples = ShortArray(960)
                audioRecord?.read(samples, 0, 960, AudioRecord.READ_BLOCKING)
                val buffer = ByteArray(2000)
                val bytes = encoder?.encode(samples, 480, buffer)
                if (bytes == 1) {
                    continue
                }
                val encryptedData = ByteArray(bytes!! + SodiumConstants.MAC_BYTES)
                val nonce = Random().randomBytes(24)
                Sodium.crypto_box_easy_afternm(encryptedData, buffer, bytes, nonce, sharedSecret)
                // Aud<4 byte session ID><8 byte seq><24 byte nonce><N byte encrypted data>
                val p = NetworkedAudioThread.ProtocolPacket("Aud", sessionID + longToBytes(seq) + nonce + encryptedData).toByteArray()
                val packet = DatagramPacket(p, p.size)
                packet.address = sessionAddress
                packet.port = 4321
                if (socket == null)
                    return
                socket?.send(packet)
                seq++
            }
        } catch(e: SocketException) {
            return
        }
    }

    override fun interrupt() {
        socket = null
        super.interrupt()
    }
}

class NetworkedAudioThread(private val keyPair: KeyPair, private val discoveryThread: DiscoveryThread, private val bitrate: Int) : Thread() {
    var socket: DatagramSocket? = null
    var keyMap: MutableMap<Long, ByteArray>? = null
    var sharedSecret: ByteArray? = null
    var audioTrack: AudioTrack? = null
    var sessionID: ByteArray = ByteArray(32)
    var decoder: OpusDecoder? = null
    var audioRecorderThread: AudioRecorderThread? = null

    public class ProtocolPacket {
        constructor(data: ByteArray, length: Int) {
            this.method = data.slice(0..2).toByteArray().toString(Charsets.UTF_8)
            this.data = data.slice(3 until length).toByteArray()
        }

        constructor(method: String, data: ByteArray) {
            this.method = method
            this.data = data
        }

        var method: String
        var data: ByteArray

        fun toByteArray(): ByteArray = method.toByteArray() + data
    }

    fun bytesToLong(bytes: ByteArray): Long {
        ByteBuffer.allocate(Long.SIZE_BYTES).apply {
            put(bytes)
            flip()
            return long
        }
    }

    override fun run() {
        keyMap = mutableMapOf()
        socket = DatagramSocket(4321)
        var sessionEstablished = false
        try {
            main@while (true) {
                val buf = ByteArray(1000)
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet)
                if (packet.length < 4)
                    continue
                val parsedPacket = ProtocolPacket(packet.data!!, packet.length)
                when (parsedPacket.method) {
                    // Key post/request
                    "Key" -> {
                        if (sessionEstablished)
                            continue@main
                        // Key<8 byte ID><32 byte pubkey><device name>
                        if (parsedPacket.data.size < 41)
                            continue@main
                        val id = bytesToLong(parsedPacket.data.slice(0..7).toByteArray())
                        val key = parsedPacket.data.slice(8..39).toByteArray()
                        // todo: maybe handle name, maybe remove it entirely from the protocol
                        val name = parsedPacket.data.slice(40 until parsedPacket.data.size).toByteArray().toString(Charsets.UTF_8)
                        keyMap!![id] = key
                        // Key<32 byte pubkey>
                        val response = ProtocolPacket("Key", keyPair.publicKey.toBytes())
                        packet.data = response.toByteArray()
                        socket?.send(packet)
                    }
                    // Session establishment
                    "Ses" -> {
                        if (sessionEstablished)
                            continue@main
                        // Ses<8 byte ID>
                        if (parsedPacket.data.size != 8)
                            continue@main
                        val id = bytesToLong(parsedPacket.data)
                        if (!keyMap!!.containsKey(id)) {
                            // Err
                            val response = ProtocolPacket("Err", ByteArray(0))
                            packet.data = response.toByteArray()
                            socket?.send(packet)
                            continue@main
                        }
                        sessionID = Random().randomBytes(4)
                        sharedSecret = ByteArray(32)
                        Sodium.crypto_box_beforenm(sharedSecret, keyMap!![id], keyPair.privateKey.toBytes())
                        sessionEstablished = true
                        // AOk<4 byte session ID>
                        val response = ProtocolPacket("AOk", sessionID)
                        packet.data = response.toByteArray()
                        val format = AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                        val attributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                        audioTrack = AudioTrack.Builder()
                            .setAudioAttributes(attributes)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(
                                48000,
                                AudioFormat.CHANNEL_OUT_STEREO,
                                AudioFormat.ENCODING_PCM_16BIT
                            ))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        audioTrack?.play()
                        decoder = OpusDecoder()
                        decoder?.init(48000, 2)
                        if (SharedObject.audioDevice != null) {
                            audioRecorderThread = AudioRecorderThread(sharedSecret, packet.address, sessionID, bitrate)
                            audioRecorderThread?.start()
                        }
                        socket?.send(packet)
                    }
                    // Audio reception
                    "Aud" -> {
                        if (!sessionEstablished)
                            continue@main
                        // Aud<4 byte session ID><8 byte seq><24 byte nonce><N byte encrypted data>
                        if (parsedPacket.data.size < 37)
                            continue@main
                        val recvSessionID = parsedPacket.data.slice(0..3).toByteArray()
                        if (!recvSessionID.contentEquals(sessionID))
                            continue@main
                        val seq = parsedPacket.data.slice(4..11).toByteArray()
                        val nonce = parsedPacket.data.slice(12..35).toByteArray()
                        val data = parsedPacket.data.slice(36 until parsedPacket.data.size).toByteArray()
                        val decryptedData = ByteArray(data.size - SodiumConstants.MAC_BYTES)
                        if (Sodium.crypto_box_open_easy_afternm(decryptedData, data, data.size, nonce, sharedSecret) != 0) {
                            Log.w("NetworkedAudioThread", "packet decryption failed")
                            continue@main
                        }
                        val samples = ShortArray(960)
                        decoder?.decode(decryptedData, samples, 480)
                        audioTrack?.write(samples, 0, 960)
                        // Ack<8 byte seq>
                        val response = ProtocolPacket("Ack", seq)
                        packet.data = response.toByteArray()
                        socket?.send(packet)
                    }
                    // Session end
                    "End" -> {
                        // End<4 byte session ID>

                        // End<4 byte session ID>
                    }
                    else -> continue@main
                }
            }
        } catch (e: SocketException) {
            Log.d("NetworkedAudioThread", "socket is closed")
            return
        }
    }

    override fun interrupt() {
        audioRecorderThread?.interrupt()
        socket?.close()
        super.interrupt()
    }
}

class NetworkingService : Service() {
    private val channelID = "NetworkedAudioForegroundChannel"

    private lateinit var discoveryThread: DiscoveryThread
    private lateinit var networkedAudioThread: NetworkedAudioThread
    lateinit var keyPair: KeyPair

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceName = intent!!.getStringExtra("com.rfnull.networkedaudio.deviceName")!!
        val bitrate = intent.getIntExtra("com.rfnull.networkedaudio.bitrate", 100)
        discoveryThread = DiscoveryThread(deviceName)
        discoveryThread.start()

        networkedAudioThread = NetworkedAudioThread(keyPair, discoveryThread, bitrate)
        networkedAudioThread.start()

        createNotificationChannel()

        val notificationIntent: Intent = if (SharedObject.serviceType == SharedObject.RunType.LISTENING)
            Intent(this, ListenerActivity::class.java)
        else
            Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

        val notification = NotificationCompat.Builder(this, channelID)
            .setContentTitle("Networked Audio")
            .setContentText("Networked Audio service is running...")
            .setSmallIcon(R.drawable.ic_audiotrack_black_24dp)
            //.setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)

        SharedObject.serviceRunning = true

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelID,
                "Networked Audio Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onCreate() {
        keyPair = KeyPair()
        super.onCreate()
    }

    override fun onDestroy() {
        Log.d("NetworkingService", "destroying the service")
        discoveryThread.interrupt()
        networkedAudioThread.interrupt()
        SharedObject.serviceRunning = false
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
