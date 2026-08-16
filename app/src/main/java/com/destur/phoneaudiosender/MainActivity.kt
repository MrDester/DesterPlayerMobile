package com.destur.phoneaudiosender

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.BitmapFactory
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.media.projection.MediaProjectionManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.text.Charsets.US_ASCII
import kotlin.text.Charsets.UTF_8

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var computerIpInput: EditText
    private lateinit var portInput: EditText
    private lateinit var mutePhoneCheckbox: CheckBox
    private lateinit var trackText: TextView
    private lateinit var artistText: TextView
    private lateinit var coverImage: ImageView
    private lateinit var coverPlaceholder: TextView
    private lateinit var trackProgressBar: ProgressBar
    private lateinit var trackPositionText: TextView
    private lateinit var trackDurationText: TextView
    private lateinit var queueContainer: LinearLayout
    private lateinit var startMenu: View
    private lateinit var playerScroll: ScrollView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastTrackSignature: String? = null
    private var lastCoverHash: Int? = null
    private val trackUiRefresh = object : Runnable {
        override fun run() {
            refreshTrackUi()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    private var connectionThread: Thread? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                updateStatus(R.string.status_not_connected)
                appendLog("Разрешение MediaProjection не выдано.")
                return@registerForActivityResult
            }

            startCaptureService(result.resultCode, data)
        }

    private val captureStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CaptureService.ACTION_STATUS) {
                return
            }

            val status = intent.getStringExtra(CaptureService.EXTRA_STATUS)
                ?: getString(R.string.status_not_connected)
            val message = intent.getStringExtra(CaptureService.EXTRA_MESSAGE)
                ?: return
            statusText.text = status
            appendLog(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                maxOf(view.paddingLeft, systemBars.left),
                maxOf(view.paddingTop, systemBars.top),
                maxOf(view.paddingRight, systemBars.right),
                maxOf(view.paddingBottom, systemBars.bottom)
            )
            insets
        }

        statusText = findViewById(R.id.statusText)
        startMenu = findViewById(R.id.startMenu)
        playerScroll = findViewById(R.id.playerScroll)
        logText = findViewById(R.id.logText)
        trackText = findViewById(R.id.trackText)
        artistText = findViewById(R.id.artistText)
        coverImage = findViewById(R.id.coverImage)
        coverPlaceholder = findViewById(R.id.coverPlaceholder)
        trackProgressBar = findViewById(R.id.trackProgressBar)
        trackPositionText = findViewById(R.id.trackPositionText)
        trackDurationText = findViewById(R.id.trackDurationText)
        queueContainer = findViewById(R.id.queueContainer)
        computerIpInput = findViewById(R.id.computerIpInput)
        portInput = findViewById(R.id.portInput)
        mutePhoneCheckbox = findViewById(R.id.mutePhoneCheckbox)
        registerCaptureStatusReceiver()

        findViewById<Button>(R.id.startMenuConnectButton).setOnClickListener {
            showPlayerScreen(R.id.connectionCard)
        }
        findViewById<Button>(R.id.startMenuPlayerButton).setOnClickListener {
            showPlayerScreen(R.id.playerCard)
        }
        findViewById<Button>(R.id.startMenuHelpButton).setOnClickListener {
            showHelp(
                "Как начать",
                "1. Запусти DesterPlayer на ПК.\n2. Нажми «Найти ПК».\n3. Нажми «Начать передачу» и разреши захват звука.\n\nТелефон и компьютер должны быть в одной Wi‑Fi сети."
            )
        }
        findViewById<Button>(R.id.startMenuSettingsButton).setOnClickListener {
            showPlayerScreen(R.id.streamingCard)
        }
        findViewById<Button>(R.id.backToMenuButton).setOnClickListener {
            playerScroll.visibility = View.GONE
            startMenu.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.menuPlayerButton).setOnClickListener {
            scrollToSection(R.id.playerCard)
        }
        findViewById<Button>(R.id.menuConnectionButton).setOnClickListener {
            scrollToSection(R.id.connectionCard)
        }
        findViewById<Button>(R.id.menuHelpButton).setOnClickListener {
            showHelp(
                "Как начать",
                "1. Запусти DesterPlayer на ПК.\n2. Нажми «Найти ПК».\n3. Нажми «Начать передачу» и разреши захват звука.\n\nТелефон и компьютер должны быть в одной Wi‑Fi сети."
            )
        }
        findViewById<Button>(R.id.menuAboutButton).setOnClickListener {
            showHelp(
                "О DesterPlayer",
                "DesterPlayer передаёт звук, название трека и обложку с телефона на компьютер напрямую по локальной сети. Интернет для передачи не нужен."
            )
        }

        findViewById<Button>(R.id.helpButton).setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add("Как подключиться")
                menu.add("Разрешение названий треков")
                menu.add("О проекте")
                setOnMenuItemClickListener { item ->
                    when (item.title.toString()) {
                        "Как подключиться" -> showHelp(
                            "Как подключиться",
                            "Запусти DesterPlayer на ПК. Телефон найдёт его автоматически в той же Wi‑Fi сети. Затем включи передачу звука."
                        )
                        "Разрешение названий треков" -> showHelp(
                            "Названия треков",
                            "Нужно разрешить DesterPlayer доступ к уведомлениям, чтобы видеть название, исполнителя и обложку текущей музыки."
                        )
                        "О проекте" -> showHelp(
                            "DesterPlayer",
                            "Передача музыки с телефона на ПК по локальной сети. Внешний сервер не используется."
                        )
                    }
                    true
                }
            }.show()
        }

        findViewById<Button>(R.id.findPcButton).setOnClickListener {
            discoverAndConnect()
        }

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            connectToComputer()
        }

        findViewById<Button>(R.id.startStreamButton).setOnClickListener {
            requestCapture()
        }

        findViewById<Button>(R.id.stopStreamButton).setOnClickListener {
            stopCapture()
        }

        findViewById<Button>(R.id.trackAccessButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (error: Exception) {
                appendLog("Не удалось открыть настройки доступа к названиям треков: ${error.message}")
            }
        }

        // Если приёмник уже запущен, подключаемся автоматически сразу после старта приложения.
        window.decorView.postDelayed({ discoverAndConnect(isAutomatic = true) }, 350L)
        uiHandler.post(trackUiRefresh)
    }

    private fun refreshTrackUi() {
        val track = CurrentTrackStore.get()
        val signature = track.title + "\u0000" + track.artist
        if (signature != lastTrackSignature) {
            trackText.text = track.title
            artistText.text = track.artist
            lastTrackSignature = signature
        }

        if (track.durationMs > 0L) {
            trackProgressBar.isIndeterminate = false
            trackProgressBar.max = track.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            trackProgressBar.progress = track.positionMs.coerceIn(0L, track.durationMs)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            trackPositionText.text = formatTime(track.positionMs)
            trackDurationText.text = formatTime(track.durationMs)
        } else {
            trackProgressBar.isIndeterminate = false
            trackProgressBar.progress = 0
            trackPositionText.text = "00:00"
            trackDurationText.text = "живой поток"
        }

        queueContainer.removeAllViews()
        if (track.queueTitles.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "♫  Очередь пока пуста\n    Выберите музыку в VKX"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sender_text_secondary))
                textSize = 13f
                setPadding(dp(6), dp(10), dp(6), dp(10))
            }
            queueContainer.addView(emptyView)
        } else {
            track.queueTitles.forEachIndexed { index, title ->
                val row = TextView(this).apply {
                    text = "%02d   %s".format(index + 1, title)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sender_text_secondary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isSelected = title == track.title
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundResource(R.drawable.bg_queue_item)
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (index == 0) 0 else dp(6)
                }
                queueContainer.addView(row, params)
            }
        }

        val cover = track.coverJpeg
        val coverHash = cover?.contentHashCode()
        if (coverHash == lastCoverHash) {
            return
        }
        lastCoverHash = coverHash
        if (cover == null) {
            coverImage.setImageDrawable(null)
            coverPlaceholder.visibility = View.VISIBLE
            return
        }

        val bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.size)
        if (bitmap != null) {
            coverImage.setImageBitmap(bitmap)
            coverPlaceholder.visibility = View.GONE
        }
    }

    private fun scrollToSection(sectionId: Int) {
        val section = findViewById<View>(sectionId)
        playerScroll.post { playerScroll.smoothScrollTo(0, section.top) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showPlayerScreen(sectionId: Int) {
        startMenu.visibility = View.GONE
        playerScroll.visibility = View.VISIBLE
        scrollToSection(sectionId)
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun showHelp(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun discoverAndConnect(isAutomatic: Boolean = false) {
        if (isAutomatic && isSocketConnected()) {
            return
        }

        updateStatus(R.string.status_searching)
        appendLog(if (isAutomatic) "Автоматически ищу DesterPlayer в локальной сети." else getString(R.string.find_pc_log))

        thread(start = true, name = "phone-audio-pc-discovery") {
            var discoveredHost: String? = null
            var discoveredPort = DISCOVERY_TCP_PORT
            try {
                DatagramSocket().use { discoverySocket ->
                    discoverySocket.broadcast = true
                    discoverySocket.soTimeout = DISCOVERY_TIMEOUT_MS
                    val request = "VKX_DISCOVER_V1".toByteArray(UTF_8)
                    val target = InetAddress.getByName("255.255.255.255")
                    discoverySocket.send(
                        DatagramPacket(
                            request,
                            request.size,
                            target,
                            DISCOVERY_UDP_PORT
                        )
                    )

                    val responseBuffer = ByteArray(512)
                    val response = DatagramPacket(responseBuffer, responseBuffer.size)
                    discoverySocket.receive(response)
                    val message = String(response.data, 0, response.length, UTF_8)
                    if (!message.startsWith("VKX_PC|")) {
                        throw IOException("Получен неизвестный ответ обнаружения")
                    }

                    discoveredHost = response.address.hostAddress
                    discoveredPort = message
                        .split('|')
                        .firstOrNull { it.startsWith("port=") }
                        ?.substringAfter("=")
                        ?.toIntOrNull()
                        ?: DISCOVERY_TCP_PORT
                }

                val host = discoveredHost ?: throw IOException("ПК не найден")
                runOnUiThread {
                    computerIpInput.setText(host)
                    portInput.setText(discoveredPort.toString())
                    appendLog("ПК найден: $host:$discoveredPort")
                    connectToComputer()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateStatus(R.string.status_not_connected)
                        appendLog("ПК не найден в локальной сети: ${error.message ?: "нет ответа"}")
                    }
                }
            }
        }
    }

    private fun registerCaptureStatusReceiver() {
        val filter = IntentFilter(CaptureService.ACTION_STATUS)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                captureStatusReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(captureStatusReceiver, filter)
        }
    }

    private fun requestCapture() {
        val host = computerIpInput.text.toString().trim()
        if (host.isEmpty()) {
            updateStatus(R.string.status_not_connected)
            appendLog(getString(R.string.empty_ip_log))
            return
        }

        val port = portInput.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            updateStatus(R.string.status_not_connected)
            appendLog(getString(R.string.invalid_port_log))
            return
        }

        val requiredPermissions = buildList {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            requestPermissions(requiredPermissions.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            return
        }

        launchProjectionPermission()
    }

    private fun launchProjectionPermission() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        closeConnection()
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, resultData)
            putExtra(CaptureService.EXTRA_HOST, computerIpInput.text.toString().trim())
            putExtra(
                CaptureService.EXTRA_PORT,
                portInput.text.toString().trim().toInt()
            )
            putExtra(
                CaptureService.EXTRA_MUTE_PHONE,
                mutePhoneCheckbox.isChecked
            )
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateStatus(R.string.status_connecting)
        appendLog("Запускаю Foreground Service и захват воспроизведения.")
    }

    private fun stopCapture() {
        startService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_STOP)
        )
        stopConnection()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return
        }

        val audioGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (audioGranted) {
            launchProjectionPermission()
        } else {
            updateStatus(R.string.status_not_connected)
            appendLog("Разрешение на запись аудио не выдано.")
        }
    }

    private fun connectToComputer() {
        if (isSocketConnected()) {
            val currentPort = portInput.text.toString().trim().toIntOrNull() ?: 0
            appendLog(
                getString(
                    R.string.connected_log,
                    computerIpInput.text.toString().trim(),
                    currentPort
                )
            )
            return
        }

        val host = computerIpInput.text.toString().trim()
        if (host.isEmpty()) {
            updateStatus(R.string.status_not_connected)
            appendLog(getString(R.string.empty_ip_log))
            return
        }

        val port = portInput.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            updateStatus(R.string.status_not_connected)
            appendLog(getString(R.string.invalid_port_log))
            return
        }

        closeConnection()
        updateStatus(R.string.status_connecting)
        appendLog(getString(R.string.connect_log))

        connectionThread = thread(start = true, name = "phone-audio-tcp-connect") {
            var newSocket: Socket? = null
            try {
                newSocket = Socket()
                newSocket.tcpNoDelay = true
                newSocket.connect(InetSocketAddress(host, port), 5000)

                val output = newSocket.getOutputStream()
                val input = newSocket.getInputStream()
                writeFrame(
                    output,
                    MessageType.HELLO,
                    "PhoneAudioSender|audio=48000;channels=2;bits=16"
                )

                val acknowledgement = readFrame(input)
                    ?: throw IOException("ПК закрыл соединение до HELLO_ACK")
                if (acknowledgement.type != MessageType.HELLO_ACK) {
                    throw IOException(
                        "Ожидался HELLO_ACK, получен тип " + acknowledgement.type
                    )
                }

                socket = newSocket
                outputStream = output
                newSocket = null

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateStatus(R.string.status_connected)
                        appendLog(getString(R.string.connected_log, host, port))
                        appendLog("HELLO_ACK получен: " + acknowledgement.payload)
                    }
                }
            } catch (error: Exception) {
                newSocket?.close()
                socket = null
                outputStream = null
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateStatus(R.string.status_not_connected)
                        appendLog(
                            getString(
                                R.string.connection_failed_log,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                    }
                }
            }
        }
    }

    private fun sendTestTone() {
        val output = outputStream
        if (output == null || !isSocketConnected()) {
            updateStatus(R.string.status_not_connected)
            appendLog(getString(R.string.stream_requires_connection))
            return
        }

        thread(start = true, name = "phone-audio-test-tone") {
            try {
                writeFrame(output, MessageType.TEST, "TEST_PCM_PLACEHOLDER")
                var phase = 0.0
                repeat(TEST_TONE_CHUNKS) {
                    val chunk = generatePcmChunk(phase)
                    phase = chunk.nextPhase
                    writeFrame(output, MessageType.AUDIO, chunk.bytes)
                    Thread.sleep(TEST_TONE_CHUNK_DURATION_MS.toLong())
                }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateStatus(R.string.status_ready)
                        appendLog(getString(R.string.stream_test_log))
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        updateStatus(R.string.status_not_connected)
                        appendLog(
                            getString(
                                R.string.connection_failed_log,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                    }
                }
            }
        }
    }

    private fun generatePcmChunk(startPhase: Double): PcmChunk {
        val samplesPerChunk = SAMPLE_RATE * TEST_TONE_CHUNK_DURATION_MS / 1000
        val bytes = ByteArray(samplesPerChunk * CHANNELS * BYTES_PER_SAMPLE)
        var phase = startPhase
        val phaseStep = 2.0 * PI * TEST_TONE_FREQUENCY / SAMPLE_RATE
        var offset = 0

        repeat(samplesPerChunk) {
            val sample = (sin(phase) * Short.MAX_VALUE * 0.2)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            repeat(CHANNELS) {
                bytes[offset] = (sample and 0xFF).toByte()
                bytes[offset + 1] = ((sample shr 8) and 0xFF).toByte()
                offset += BYTES_PER_SAMPLE
            }

            phase += phaseStep
            if (phase >= 2.0 * PI) {
                phase -= 2.0 * PI
            }
        }

        return PcmChunk(bytes, phase)
    }

    private fun stopConnection() {
        val output = outputStream
        if (output != null && isSocketConnected()) {
            try {
                writeFrame(output, MessageType.STOP, "user-stop")
            } catch (_: Exception) {
                // Соединение всё равно будет закрыто ниже.
            }
        }

        closeConnection()
        updateStatus(R.string.status_stopped)
        appendLog(getString(R.string.connection_closed_log))
    }

    private fun isSocketConnected(): Boolean {
        val currentSocket = socket
        return currentSocket != null &&
            currentSocket.isConnected &&
            !currentSocket.isClosed
    }

    private fun closeConnection() {
        connectionThread?.interrupt()
        connectionThread = null
        outputStream = null
        socket?.close()
        socket = null
    }

    private fun writeFrame(output: OutputStream, type: Int, payload: String) {
        writeFrame(output, type, payload.toByteArray(UTF_8))
    }

    private fun writeFrame(output: OutputStream, type: Int, payloadBytes: ByteArray) {
        if (payloadBytes.size > MAX_PAYLOAD_SIZE) {
            throw IOException("Слишком большой payload")
        }

        val header = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(header, destinationOffset = 0)
        header[4] = PROTOCOL_VERSION.toByte()
        header[5] = type.toByte()
        writeUInt32LittleEndian(header, 6, payloadBytes.size)

        synchronized(output) {
            output.write(header)
            output.write(payloadBytes)
            output.flush()
        }
    }

    private fun readFrame(input: InputStream): ProtocolFrame? {
        val header = ByteArray(HEADER_SIZE)
        if (!readFully(input, header)) {
            return null
        }

        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IOException("Неверная сигнатура протокола")
        }
        if ((header[4].toInt() and 0xFF) != PROTOCOL_VERSION) {
            throw IOException("Неподдерживаемая версия протокола")
        }

        val payloadLength = readUInt32LittleEndian(header, 6)
        if (payloadLength > MAX_PAYLOAD_SIZE) {
            throw IOException("Слишком большой payload")
        }

        val payloadBytes = ByteArray(payloadLength)
        if (!readFully(input, payloadBytes)) {
            return null
        }

        return ProtocolFrame(
            type = header[5].toInt() and 0xFF,
            payload = payloadBytes.toString(UTF_8)
        )
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

    private fun updateStatus(statusRes: Int) {
        statusText.setText(statusRes)
    }

    private fun appendLog(message: String) {
        logText.append("\n$message")
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(trackUiRefresh)
        closeConnection()
        unregisterReceiver(captureStatusReceiver)
        super.onDestroy()
    }

    private data class ProtocolFrame(
        val type: Int,
        val payload: String
    )

    private data class PcmChunk(
        val bytes: ByteArray,
        val nextPhase: Double
    )

    private object MessageType {
        const val HELLO = 1
        const val HELLO_ACK = 2
        const val TEST = 3
        const val STOP = 4
        const val AUDIO = 7
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val HEADER_SIZE = 10
        const val MAX_PAYLOAD_SIZE = 4 * 1024 * 1024
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BYTES_PER_SAMPLE = 2
        const val TEST_TONE_FREQUENCY = 440.0
        const val TEST_TONE_CHUNK_DURATION_MS = 100
        const val TEST_TONE_CHUNKS = 30
        const val DISCOVERY_UDP_PORT = 37820
        const val DISCOVERY_TCP_PORT = 37821
        const val DISCOVERY_TIMEOUT_MS = 1200
        const val PERMISSIONS_REQUEST_CODE = 7001
        val MAGIC = "VXP1".toByteArray(US_ASCII)
    }
}
