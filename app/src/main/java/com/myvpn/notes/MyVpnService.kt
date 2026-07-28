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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SNIHostName
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
    private var proxyServer: UniversalProxyServer? = null

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
            val cfIp = "104.26.6.213" // Точный IP из твоего конфига

            log("🔑 UUID: ${uuid.take(8)}...")
            log("🌐 Выход на IP: $cfIp (SNI: $workerHost)")
            log("✂️ Включена TLS-Фрагментация пакетов (Bypass DPI)...")

            proxyServer = UniversalProxyServer(uuid, workerHost, cfIp)
            proxyServer?.start(10808)

            log("✅ Прокси запущен на порту 10808")

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

            log("🎉 ГОТОВО! Открывай сайт в браузере...")

        } catch (e: Exception) {
            log("💥 СБОЙ ЗАПУСКА: ${e.message}")
            stopVpnInternal()
        }
    }

    @Synchronized
    private fun stopVpnInternal() {
        try {
            proxyServer?.stop()
            proxyServer = null
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
// ПЕРЕХВАТЧИК И ДРОБИТЕЛЬ ПАКЕТОВ (TLS FRAGMENTATION IN KOTLIN)
// =========================================================================
class FragmentingOutputStream(private val delegate: OutputStream) : OutputStream() {
    private var isFragmented = false

    override fun write(b: Int) { delegate.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) {
        // Дробление только первого пакета TLS ClientHello (байты 0x16 0x03)
        if (!isFragmented && len > 5 && b[off] == 0x16.toByte() && b[off + 1] == 0x03.toByte()) {
            isFragmented = true
            val chunk1 = minOf(25, len) // Первый фрагмент: 25 байт
            delegate.write(b, off, chunk1)
            delegate.flush()

            try { Thread.sleep(15) } catch (e: Exception) {} // Задержка 15 мс

            val remaining = len - chunk1
            if (remaining > 0) {
                delegate.write(b, off + chunk1, remaining) // Второй фрагмент: остаток
                delegate.flush()
            }
        } else {
            delegate.write(b, off, len)
        }
    }

    override fun flush() { delegate.flush() }
    override fun close() { delegate.close() }
}

class FragmentedSocket(private val realSocket: Socket) : Socket() {
    private val fragOut by lazy { FragmentingOutputStream(realSocket.getOutputStream()) }
    override fun getOutputStream(): OutputStream = fragOut
    override fun getInputStream(): InputStream = realSocket.getInputStream()
    override fun close() = realSocket.close()
    override fun isConnected(): Boolean = realSocket.isConnected
    override fun isClosed(): Boolean = realSocket.isClosed
}

// =========================================================================
// УНИВЕРСАЛЬНЫЙ ДВИЖОК С ВЫХОДОМ НА ПРЯМОЙ IP И ФРАГМЕНТАЦИЕЙ
// =========================================================================
class UniversalProxyServer(
    private val uuid: String,
    private val workerHost: String,
    private val cfIp: String
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    fun start(port: Int) {
        isRunning = true
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        thread(name = "ProxyThread") {
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

            val firstByte = input.read()
            if (firstByte == -1) { client.close(); return }

            var targetAddr = ""
            var targetPort = 80
            var addrTypeByte: Byte = 0x02
            var isHttpCONNECT = false

            if (firstByte == 5) {
                // SOCKS5
                val nmethods = input.read()
                val methods = ByteArray(nmethods)
                input.read(methods)
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                input.read(); input.read(); input.read()
                val atyp = input.read()

                if (atyp == 1) {
                    val ipv4 = ByteArray(4)
                    input.read(ipv4)
                    targetAddr = ipv4.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    addrTypeByte = 0x01
                } else if (atyp == 3) {
                    val len = input.read()
                    val domainBytes = ByteArray(len)
                    input.read(domainBytes)
                    targetAddr = String(domainBytes, Charsets.UTF_8)
                    addrTypeByte = 0x02
                } else if (atyp == 4) {
                    val ipv6 = ByteArray(16)
                    input.read(ipv6)
                    targetAddr = "127.0.0.1"
                    addrTypeByte = 0x03
                }

                val p1 = input.read()
                val p2 = input.read()
                targetPort = (p1 shl 8) or p2

                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                output.flush()

                MyVpnService.log("🌐 [SOCKS5] $targetAddr:$targetPort")

            } else {
                // HTTP PROXY
                val lineBytes = ByteArrayOutputStream()
                lineBytes.write(firstByte)
                while (true) {
                    val b = input.read()
                    if (b == -1 || b == '\n'.code) break
                    if (b != '\r'.code) lineBytes.write(b)
                }
                val requestLine = String(lineBytes.toByteArray(), Charsets.UTF_8)

                val parts = requestLine.split(" ")
                if (parts.size >= 2) {
                    val method = parts[0]
                    val hostPortStr = parts[1]

                    if (method.equals("CONNECT", ignoreCase = true)) {
                        isHttpCONNECT = true
                        val hp = hostPortStr.split(":")
                        targetAddr = hp[0]
                        targetPort = if (hp.size > 1) hp[1].toIntOrNull() ?: 443 else 443
                    } else {
                        var cleanUrl = hostPortStr.replace("http://", "").replace("https://", "")
                        val slashIdx = cleanUrl.indexOf('/')
                        if (slashIdx != -1) cleanUrl = cleanUrl.substring(0, slashIdx)
                        val hp = cleanUrl.split(":")
                        targetAddr = hp[0]
                        targetPort = if (hp.size > 1) hp[1].toIntOrNull() ?: 80 else 80
                    }
                }

                while (true) {
                    val hLine = readLine(input)
                    if (hLine.isEmpty()) break
                }

                if (isHttpCONNECT) {
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }

                MyVpnService.log("🌐 [HTTP] $targetAddr:$targetPort")
            }

            if (targetAddr.isEmpty()) { client.close(); return }

            connectToWorkerAndPipe(client, uuid, workerHost, cfIp, addrTypeByte, targetAddr, targetPort)

        } catch (e: Exception) {
            MyVpnService.log("⚠️ Ошибка: ${e.message}")
            try { client.close() } catch (e2: Exception) {}
        }
    }

    private fun readLine(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
    }

    private fun connectToWorkerAndPipe(
        client: Socket,
        uuid: String,
        workerHost: String,
        cfIp: String,
        addrTypeByte: Byte,
        targetAddr: String,
        targetPort: Int
    ) {
        var tlsSocket: Socket? = null
        try {
            // 1. Создаем сокет НАПРЯМУЮ к IP Cloudflare (104.26.6.213)
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(cfIp, 443), 5000)

            // 2. Оборачиваем сокет в сокет-дробитель пакетов
            val fragSocket = FragmentedSocket(rawSocket)

            // 3. Запускаем SSL поверх раздробленного сокета с указанием SNI
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory.createSocket(fragSocket, workerHost, 443, true) as SSLSocket

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(workerHost))
                ssl.sslParameters = params
            }
            ssl.startHandshake()

            val tlsIn = ssl.inputStream
            val tlsOut = ssl.outputStream

            // 4. WebSocket Upgrade Запрос
            val upgradeReq = "GET / HTTP/1.1\r\n" +
                    "Host: $workerHost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"

            tlsOut.write(upgradeReq.toByteArray(Charsets.UTF_8))
            tlsOut.flush()

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

            // 5. VLESS 24-байтовый Заголовок
            val vlessHeader = ByteArrayOutputStream()
            vlessHeader.write(0)
            vlessHeader.write(uuidToBytes(uuid))
            vlessHeader.write(0)
            vlessHeader.write(1)
            vlessHeader.write((targetPort shr 8) and 0xFF)
            vlessHeader.write(targetPort and 0xFF)
            vlessHeader.write(addrTypeByte.toInt())

            val addrBytes = targetAddr.toByteArray(Charsets.UTF_8)
            if (addrTypeByte == 0x02.toByte()) {
                vlessHeader.write(addrBytes.size)
            }
            vlessHeader.write(addrBytes)

            sendWsFrame(tlsOut, vlessHeader.toByteArray())
            MyVpnService.log("⚡ Успех! Кабель через $cfIp -> $targetAddr:$targetPort")

            // 6. Прокачка
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
            MyVpnService.log("💥 Сбой соединения ($targetAddr): ${e.message}")
        } finally {
            try { client.close() } catch (e: Exception) {}
            try { tlsSocket?.close() } catch (e: Exception) {}
        }
    }

    private fun sendWsFrame(out: OutputStream, payload: ByteArray) {
        val len = payload.size
        out.write(0x82)
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
