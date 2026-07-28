package com.myvpn.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class MyVpnService : VpnService() {

    companion object {
        @Volatile
        var isRunning = false
        var onLogMessage: ((String) -> Unit)? = null

        fun log(msg: String) {
            onLogMessage?.invoke(msg)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var socksServer: PureKotlinSocksServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "STOP") {
            log("🛑 Остановка...")
            stopVpnInternal()
            return START_NOT_STICKY
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Сбой инициализации", Toast.LENGTH_LONG).show()
            stopVpnInternal()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            log("⚠️ Ошибка Foreground: ${e.message}")
        }

        thread { startVpnInternal() }
        return START_STICKY
    }

    private fun startVpnInternal() {
        if (isRunning) return

        try {
            val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            val uuid = prefs.getString("user_uuid", "d8116f0f-5ad5-4f07-b63b-0877a3113ca2") ?: ""
            val workerHost = "dark-poetry-8a03.tio-rex-ultra.workers.dev"

            log("🔑 UUID: ${uuid.take(8)}...")
            log("🚀 Запуск Чистого Kotlin SOCKS5 Сервера...")

            // 1. Запускаем чистый Kotlin SOCKS5 Прокси на порту 10808
            socksServer = PureKotlinSocksServer(uuid, workerHost)
            socksServer?.start(10808)

            log("✅ Чистый Kotlin Двигатель на порту 10808!")

            // 2. Настраиваем системный VPN интерфейс
            val builder = Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .setMtu(1500)
                .setSession("Заметки")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", 10808))
            }

            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

            vpnInterface = builder.establish()
            isRunning = true

            log("🎉 ВПН УСПЕШНО ПОДКЛЮЧЕН (PURE KOTLIN)!")

        } catch (e: Exception) {
            log("💥 КРИТИЧЕСКИЙ СБОЙ: ${e.message}")
            stopVpnInternal()
        }
    }

    @Synchronized
    private fun stopVpnInternal() {
        try {
            socksServer?.stop()
            socksServer = null
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log("👋 Отключено.")
    }

    override fun onDestroy() { stopVpnInternal(); super.onDestroy() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, MyVpnService::class.java).apply { action = "STOP" }
        val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "vpn")
            .setContentTitle("Заметки")
            .setContentText("Защита активна")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключиться", pendingStopIntent)
            .build()
    }
}

// =========================================================================
// ЧИСТЫЙ KOTLIN SOCKS5 -> VLESS over WebSocket ДВИЖОК (БЕЗ C++ И GO)
// =========================================================================
class PureKotlinSocksServer(private val uuid: String, private val workerHost: String) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    fun start(port: Int) {
        isRunning = true
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        thread(name = "KotlinSocksThread") {
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    thread { handleClient(clientSocket) }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // SOCKS5 Handshake
            val ver = input.read()
            if (ver != 5) { client.close(); return }
            val nmethods = input.read()
            val methods = ByteArray(nmethods)
            input.read(methods)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // SOCKS5 Request
            val ver2 = input.read()
            val cmd = input.read()
            input.read() // RSV
            val atyp = input.read()

            var targetAddr = ""
            var addrTypeByte: Byte = 0x02 // Domain

            if (atyp == 1) { // IPv4
                val ipv4 = ByteArray(4)
                input.read(ipv4)
                targetAddr = ipv4.joinToString(".") { (it.toInt() and 0xFF).toString() }
                addrTypeByte = 0x01
            } else if (atyp == 3) { // Domain
                val len = input.read()
                val domainBytes = ByteArray(len)
                input.read(domainBytes)
                targetAddr = String(domainBytes, Charsets.UTF_8)
                addrTypeByte = 0x02
            } else if (atyp == 4) { // IPv6
                val ipv6 = ByteArray(16)
                input.read(ipv6)
                targetAddr = "127.0.0.1"
                addrTypeByte = 0x03
            }

            val p1 = input.read()
            val p2 = input.read()
            val targetPort = (p1 shl 8) or p2

            // SOCKS5 Reply Success
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
            output.flush()

            // Подключение к Cloudflare Worker и прокачка VLESS
            connectToWorkerAndPipe(client, uuid, workerHost, addrTypeByte, targetAddr, targetPort)

        } catch (e: Exception) {
            try { client.close() } catch (e2: Exception) {}
        }
    }

    private fun connectToWorkerAndPipe(
        client: Socket,
        uuid: String,
        workerHost: String,
        addrTypeByte: Byte,
        targetAddr: String,
        targetPort: Int
    ) {
        var tlsSocket: Socket? = null
        try {
            val sslFactory = SSLSocketFactory.getDefault()
            tlsSocket = sslFactory.createSocket(workerHost, 443)
            val ssl = tlsSocket as SSLSocket
            ssl.startHandshake()

            val tlsIn = ssl.inputStream
            val tlsOut = ssl.outputStream

            // 1. WebSocket Upgrade HTTP Запрос
            val upgradeReq = "GET / HTTP/1.1\r\n" +
                    "Host: $workerHost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"

            tlsOut.write(upgradeReq.toByteArray(Charsets.UTF_8))
            tlsOut.flush()

            // Читаем заголовки ответа HTTP 101
            val headerBuffer = ByteArray(1024)
            var readBytes = 0
            while (readBytes < headerBuffer.size) {
                val b = tlsIn.read()
                if (b == -1) break
                headerBuffer[readBytes++] = b.toByte()
                if (readBytes >= 4 &&
                    headerBuffer[readBytes - 4] == 13.toByte() &&
                    headerBuffer[readBytes - 3] == 10.toByte() &&
                    headerBuffer[readBytes - 2] == 13.toByte() &&
                    headerBuffer[readBytes - 1] == 10.toByte()
                ) {
                    break
                }
            }

            // 2. Формируем 24-байтовый VLESS Заголовок
            val vlessHeader = ByteArrayOutputStream()
            vlessHeader.write(0) // Version
            vlessHeader.write(uuidToBytes(uuid)) // 16 байт UUID
            vlessHeader.write(0) // Addon length
            vlessHeader.write(1) // Command TCP
            vlessHeader.write((targetPort shr 8) and 0xFF)
            vlessHeader.write(targetPort and 0xFF)
            vlessHeader.write(addrTypeByte.toInt())

            val addrBytes = targetAddr.toByteArray(Charsets.UTF_8)
            if (addrTypeByte == 0x02.toByte()) {
                vlessHeader.write(addrBytes.size)
            }
            vlessHeader.write(addrBytes)

            // Отправляем VLESS заголовок в WebSocket кадре
            sendWsFrame(tlsOut, vlessHeader.toByteArray())

            // 3. Двунаправленный мост
            val t1 = thread {
                try {
                    val buffer = ByteArray(8192)
                    val clientIn = client.getInputStream()
                    var len: Int
                    while (clientIn.read(buffer).also { len = it } != -1) {
                        sendWsFrame(tlsOut, buffer.copyOf(len))
                    }
                } catch (e: Exception) {}
            }

            val t2 = thread {
                try {
                    val clientOut = client.getOutputStream()
                    while (true) {
                        val payload = readWsFramePayload(tlsIn) ?: break
                        clientOut.write(payload)
                        clientOut.flush()
                    }
                } catch (e: Exception) {}
            }

            t1.join()
            t2.join()

        } catch (e: Exception) {
            // Игнорируем закрытие
        } finally {
            try { client.close() } catch (e: Exception) {}
            try { tlsSocket?.close() } catch (e: Exception) {}
        }
    }

    private fun sendWsFrame(out: OutputStream, payload: ByteArray) {
        val len = payload.size
        out.write(0x82) // Binary Frame
        if (len <= 125) {
            out.write(len or 0x80)
        } else if (len <= 65535) {
            out.write(126 or 0x80)
            out.write((len shr 8) and 0xFF)
            out.write(len and 0xFF)
        } else {
            out.write(127 or 0x80)
            for (i in 7 downTo 0) {
                out.write((len ushr (i * 8)) and 0xFF)
            }
        }
        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        out.write(mask)
        val maskedPayload = ByteArray(len)
        for (i in 0 until len) {
            maskedPayload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        out.write(maskedPayload)
        out.flush()
    }

    private fun readWsFramePayload(input: InputStream): ByteArray? {
        val b1 = input.read()
        if (b1 == -1) return null
        val b2 = input.read()
        if (b2 == -1) return null

        val masked = (b2 and 0x80) != 0
        var payloadLen = b2 and 0x7F

        if (payloadLen == 126) {
            val p1 = input.read()
            val p2 = input.read()
            payloadLen = (p1 shl 8) or p2
        } else if (payloadLen == 127) {
            for (i in 0 until 8) input.read()
            payloadLen = 8192
        }

        val maskKey = ByteArray(4)
        if (masked) { input.read(maskKey) }

        val payload = ByteArray(payloadLen)
        var read = 0
        while (read < payloadLen) {
            val r = input.read(payload, read, payloadLen - read)
            if (r == -1) break
            read += r
        }

        if (masked) {
            for (i in 0 until read) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return payload
    }

    private fun uuidToBytes(uuidStr: String): ByteArray {
        val clean = uuidStr.replace("-", "")
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
