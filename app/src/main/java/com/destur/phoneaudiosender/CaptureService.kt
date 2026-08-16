package com.destur.phoneaudiosender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.text.Charsets.US_ASCII
import kotlin.text.Charsets.UTF_8

class CaptureService : Service() {
    @Volatile
    private var running = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var socket: Socket? = null

    private var captureThread: Thread? = null
    private var controlThread: Thread? = null
    private var previousPhoneVolume: Int? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}, startId=$startId")
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_START || captureThread?.isAlive == true) {
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "Foreground service started")
        } catch (error: Exception) {
            Log.e(TAG, "startForeground failed", error)
            publishStatus(
                "Ошибка запуска",
                error.message ?: "Не удалось запустить Foreground Service."
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = getResultData(intent)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val mutePhone = intent.getBooleanExtra(EXTRA_MUTE_PHONE, true)

        if (resultCode != Activity.RESULT_OK || resultData == null || host.isNullOrBlank() || port !in 1..65535) {
            publishStatus(
                "Ошибка",
                "Данные захвата: resultCode=$resultCode, " +
                    "resultData=${resultData != null}, host=${host ?: "null"}, port=$port"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        Log.i(TAG, "Starting capture thread")
        captureThread = thread(start = true, name = "phone-audio-capture") {
            runCapture(resultCode, resultData, host, port, mutePhone)
        }
        return START_NOT_STICKY
    }

    private fun runCapture(
        resultCode: Int,
        resultData: Intent,
        host: String,
        port: Int,
        mutePhone: Boolean
    ) {
        var localSocket: Socket? = null
        var localProjection: MediaProjection? = null
        var localRecord: AudioRecord? = null

        try {
            Log.i(TAG, "runCapture started: host=$host port=$port")
            publishStatus("Подключение", "Подключение сервиса к ПК.")
            localSocket = Socket()
            localSocket.tcpNoDelay = true
            localSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = localSocket

            val output = localSocket.getOutputStream()
            val input = localSocket.getInputStream()
            writeFrame(
                output,
                MESSAGE_HELLO,
                "PhoneAudioSender|audio=48000;channels=2;bits=16"
            )

            val acknowledgement = readFrame(input)
                ?: throw IOException("ПК закрыл соединение до HELLO_ACK.")
            if (acknowledgement.type != MESSAGE_HELLO_ACK) {
                throw IOException("Ожидался HELLO_ACK, получен тип " + acknowledgement.type)
            }
            publishStatus("Протокол подтверждён", "HELLO_ACK получен от ПК.")
            Log.i(TAG, "HELLO_ACK received")
            var lastTrackPayload: String? = null
            var lastCoverHash: Int? = null
            var lastQueuePayload: String? = null
            lastTrackPayload = sendTrackIfChanged(output, lastTrackPayload)
            lastCoverHash = sendCoverIfChanged(output, lastCoverHash)
            lastQueuePayload = sendQueueIfChanged(output, lastQueuePayload)
            controlThread = thread(start = true, name = "phone-audio-media-controls") {
                runControlReader(input)
            }

            val projectionManager =
                getSystemService(MediaProjectionManager::class.java)
            localProjection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw SecurityException("MediaProjection недоступен.")
            mediaProjection = localProjection
            Log.i(TAG, "MediaProjection created")

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(localProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minimumBuffer, AUDIO_FRAME_SIZE * 4)

            localRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            audioRecord = localRecord
            Log.i(TAG, "AudioRecord created: bufferSize=$bufferSize minimumBuffer=$minimumBuffer")

            localRecord.startRecording()
            if (localRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("AudioRecord не перешёл в состояние записи.")
            }

            if (mutePhone) {
                mutePhoneMediaStream()
                Log.i(TAG, "Звук телефона временно отключён")
            }

            publishStatus(
                "Передача звука",
                "Захват запущен. Воспроизведи музыку в VK X."
            )
            val buffer = ByteArray(AUDIO_FRAME_SIZE)
            var framesRead = 0
            var nonZeroFrames = 0

            while (running) {
                val bytesRead = localRecord.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING
                )
                if (bytesRead < 0) {
                    throw IOException("AudioRecord.read вернул ошибку: " + bytesRead)
                }
                if (bytesRead == 0) {
                    continue
                }

                val payload = if (bytesRead == buffer.size) {
                    buffer
                } else {
                    buffer.copyOf(bytesRead)
                }
                writeFrame(output, MESSAGE_AUDIO, payload)
                framesRead++
                if (containsNonZero(payload)) {
                    nonZeroFrames++
                }

                if (framesRead % 10 == 0) {
                    lastTrackPayload = sendTrackIfChanged(output, lastTrackPayload)
                    lastCoverHash = sendCoverIfChanged(output, lastCoverHash)
                    lastQueuePayload = sendQueueIfChanged(output, lastQueuePayload)
                    Log.i(
                        TAG,
                        "PCM frame=$framesRead bytes=$bytesRead nonZeroFrames=$nonZeroFrames"
                    )
                }

                if (framesRead == ZERO_AUDIO_CHECK_FRAMES && nonZeroFrames == 0) {
                    publishStatus(
                        "Захват без сигнала",
                        "PCM идёт, но пока только нули. VK X мог запретить захват."
                    )
                } else if (framesRead % STATUS_FRAME_INTERVAL == 0 && nonZeroFrames > 0) {
                    publishStatus(
                        "Передача звука",
                        "Передано PCM-кадров: " + framesRead
                    )
                }
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Capture permission failed", error)
            publishStatus(
                "Ошибка разрешения",
                error.message ?: "Android не разрешил захват воспроизведения."
            )
        } catch (error: Exception) {
            Log.e(TAG, "Capture failed", error)
            publishStatus(
                "Ошибка",
                error.javaClass.simpleName + ": " + (error.message ?: "без сообщения")
            )
        } finally {
            Log.i(TAG, "runCapture cleanup")
            localRecord?.let {
                try {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                } catch (_: Exception) {
                }
                it.release()
            }
            localProjection?.stop()
            localSocket?.close()
            controlThread?.interrupt()
            controlThread = null
            restorePhoneMediaStream()
            audioRecord = null
            mediaProjection = null
            socket = null
            running = false
            captureThread = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopCapture() {
        Log.i(TAG, "stopCapture requested")
        cleanupCapture()
        publishStatus("Передача остановлена", "Захват и TCP-соединение закрыты.")
        stopSelf()
    }

    private fun cleanupCapture() {
        running = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        mediaProjection?.stop()
        socket?.close()
        captureThread?.interrupt()
        restorePhoneMediaStream()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun mutePhoneMediaStream() {
        if (previousPhoneVolume != null) {
            return
        }

        val audioManager = getSystemService(AudioManager::class.java)
        previousPhoneVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun restorePhoneMediaStream() {
        val savedVolume = previousPhoneVolume ?: return
        previousPhoneVolume = null
        getSystemService(AudioManager::class.java)
            .setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
        Log.i(TAG, "Громкость телефона восстановлена: $savedVolume")
    }

    private fun containsNonZero(bytes: ByteArray): Boolean {
        for (value in bytes) {
            if (value.toInt() != 0) {
                return true
            }
        }
        return false
    }

    private fun publishStatus(status: String, message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("DesterPlayer")
            .setContentText("Передача звука активна")
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Остановить",
                PendingIntent.getService(
                    this,
                    STOP_REQUEST_CODE,
                    Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Передача звука",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun getResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun writeFrame(output: OutputStream, type: Int, payload: String) {
        writeFrame(output, type, payload.toByteArray(UTF_8))
    }

    private fun runControlReader(input: InputStream) {
        try {
            while (running) {
                val frame = readFrame(input) ?: break
                if (frame.type != MESSAGE_CONTROL) {
                    continue
                }

                val command = String(frame.payload, UTF_8)
                val accepted = CurrentTrackStore.sendControl(command)
                Log.i(TAG, "Media control $command accepted=$accepted")
            }
        } catch (error: Exception) {
            if (running) {
                Log.w(TAG, "Control channel closed: ${error.message}")
            }
        }
    }

    private fun sendTrackIfChanged(output: OutputStream, previousPayload: String?): String {
        val track = CurrentTrackStore.get()
        val payload = track.title + "\n" +
            track.artist + "\n" +
            track.durationMs + "\n" +
            track.positionMs + "\n" +
            track.isPlaying
        if (payload != previousPayload) {
            writeFrame(output, MESSAGE_TRACK, payload)
            Log.i(TAG, "Track metadata sent: ${track.title} — ${track.artist}")
        }
        return payload
    }

    private fun sendCoverIfChanged(output: OutputStream, previousHash: Int?): Int? {
        val cover = CurrentTrackStore.get().coverJpeg ?: return null
        val hash = cover.contentHashCode()
        if (hash != previousHash) {
            writeFrame(output, MESSAGE_COVER, cover)
            Log.i(TAG, "Album cover sent: ${cover.size} bytes")
        }
        return hash
    }

    private fun sendQueueIfChanged(output: OutputStream, previousPayload: String?): String {
        val payload = CurrentTrackStore.get().queueTitles
            .joinToString("\n") { it.replace('\n', ' ').trim() }
        if (payload != previousPayload) {
            writeFrame(output, MESSAGE_QUEUE, payload)
            Log.i(TAG, "Queue metadata sent: ${CurrentTrackStore.get().queueTitles.size} items")
        }
        return payload
    }

    private fun writeFrame(output: OutputStream, type: Int, payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD_SIZE) {
            throw IOException("Слишком большой payload.")
        }

        val header = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(header, destinationOffset = 0)
        header[4] = PROTOCOL_VERSION.toByte()
        header[5] = type.toByte()
        writeUInt32LittleEndian(header, 6, payload.size)

        synchronized(output) {
            output.write(header)
            output.write(payload)
            output.flush()
        }
    }

    private fun readFrame(input: InputStream): ProtocolFrame? {
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header)) {
            return null
        }
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IOException("Неверная сигнатура протокола.")
        }
        if ((header[4].toInt() and 0xFF) != PROTOCOL_VERSION) {
            throw IOException("Неподдерживаемая версия протокола.")
        }

        val payloadLength = readUInt32LittleEndian(header, 6)
        if (payloadLength > MAX_PAYLOAD_SIZE) {
            throw IOException("Слишком большой payload.")
        }
        val payload = ByteArray(payloadLength)
        if (!readFully(input, payload)) {
            return null
        }
        return ProtocolFrame(header[5].toInt() and 0xFF, payload)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) {
                return false
            }
            offset += count
        }
        return true
    }

    private fun writeUInt32LittleEndian(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readUInt32LittleEndian(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        cleanupCapture()
        super.onDestroy()
    }

    private data class ProtocolFrame(
        val type: Int,
        val payload: ByteArray
    )

    companion object {
        private const val TAG = "PhoneAudioSender"
        const val ACTION_START = "com.destur.phoneaudiosender.action.START_CAPTURE"
        const val ACTION_STOP = "com.destur.phoneaudiosender.action.STOP_CAPTURE"
        const val ACTION_STATUS = "com.destur.phoneaudiosender.action.CAPTURE_STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_MUTE_PHONE = "mute_phone"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val NOTIFICATION_CHANNEL_ID = "phone_audio_capture"
        const val NOTIFICATION_ID = 1001
        const val STOP_REQUEST_CODE = 1002
        const val CONNECT_TIMEOUT_MS = 5000
        const val PROTOCOL_VERSION = 1
        const val HEADER_SIZE = 10
        const val MAX_PAYLOAD_SIZE = 4 * 1024 * 1024
        const val SAMPLE_RATE = 48000
        const val AUDIO_FRAME_SIZE = 19200
        const val ZERO_AUDIO_CHECK_FRAMES = 10
        const val STATUS_FRAME_INTERVAL = 50
        const val MESSAGE_HELLO = 1
        const val MESSAGE_HELLO_ACK = 2
        const val MESSAGE_AUDIO = 7
        const val MESSAGE_TRACK = 8
        const val MESSAGE_CONTROL = 9
        const val MESSAGE_COVER = 10
        const val MESSAGE_QUEUE = 11
        val MAGIC = "VXP1".toByteArray(US_ASCII)
    }
}
