package com.myvpn.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.heiher.hev.socks5.tunnel.HevSocks5Tunnel
import libXray.LibXray
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
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
    @Volatile
    private var isStopping = false
    @Volatile
    private var isHevRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "STOP") {
            log("🛑 Команда остановки...")
            thread { stopVpnInternal() }
            return START_NOT_STICKY
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Сбой инициализации (Code: 0x80004005)", Toast.LENGTH_LONG).show()
            thread { stopVpnInternal() }
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
            log("⚠️ Уведомление: ${e.message}")
        }

        thread { startVpnInternal() }

        return START_STICKY
    }

    private fun startVpnInternal() {
        if (isRunning) return
        isStopping = false

        try {
            val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            val uuid = prefs.getString("user_uuid", "d8116f0f-5ad5-4f07-b63b-0877a3113ca2") ?: ""

            log("🔑 UUID: ${uuid.take(8)}...")

            // Очистка старых процессов
            try { LibXray.stopXray() } catch (e: Exception) {}
            Thread.sleep(300)

            // 1. ЗАПУСК XRAY (ТОЛЬКО SOCKS -> VLESS)
            log("⚙️ Запуск ядра...")
            val rawJson = generateUltraMinimalConfig(uuid)
            val base64Config = Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val result = LibXray.runXray(base64Config)
            // Если в ответе есть "error", значит JSON битый
            if (result.contains("error", ignoreCase = true)) {
                log("❌ Ошибка конфига!")
            }

            // Ждем порт 10808
            var attempts = 0
            while (!checkProxyPort(10808) && attempts < 30) {
                Thread.sleep(200)
                attempts++
            }

            if (!checkProxyPort(10808)) {
                log("❌ Порт 10808 не открылся!")
                stopVpnInternal()
                return
            }

            log("✅ Ядро готово!")

            // 2. TUN ИНТЕРФЕЙС
            val builder = Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .setMtu(1500)
                .setSession("Заметки")

            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}

            vpnInterface = builder.establish()
            val tunFd = vpnInterface?.fd ?: -1

            if (tunFd == -1) {
                log("❌ Ошибка TUN!")
                stopVpnInternal()
                return
            }

            // 3. HEV SOCKS5 TUNNEL
            val ymlFile = File(filesDir, "socks.yml")
            ymlFile.writeText("tunnel:\n  mtu: 1500\nsocks5:\n  port: 10808\n  address: 127.0.0.1\n  udp: 'udp'\nmisc:\n  task-stack-size: 8192")

            isRunning = true
            isHevRunning = true

            thread(name = "HevThread") {
                try {
                    HevSocks5Tunnel.hev_socks5_tunnel_main(ymlFile.absolutePath, tunFd)
                } catch (e: Throwable) {
                    log("⚠️ Поток закрыт")
                } finally {
                    isHevRunning = false
                }
            }

            log("🎉 СОЕДИНЕНО!")

        } catch (e: Exception) {
            log("💥 СБОЙ: ${e.message}")
            stopVpnInternal()
        }
    }

    private fun checkProxyPort(port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
        } catch (e: Exception) { false }
    }

    // МАКСИМАЛЬНО ЧИСТЫЙ КОНФИГ БЕЗ ФАЙЛОВ GEOIP/GEOSITE
    private fun generateUltraMinimalConfig(uuid: String): String {
        val workerDomain = "dark-poetry-8a03.tio-rex-ultra.workers.dev"
        val cfIp = "104.26.6.213" // Прямой IP Cloudflare

        return """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [{
            "port": 10808,
            "listen": "127.0.0.1",
            "protocol": "socks",
            "settings": { "auth": "noauth", "udp": true }
          }],
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {
                "vnext": [{
                  "address": "$cfIp",
                  "port": 443,
                  "users": [{ "id": "$uuid", "encryption": "none", "level": 8 }]
                }]
              },
              "streamSettings": {
                "network": "ws",
                "security": "tls",
                "tlsSettings": {
                  "serverName": "$workerDomain",
                  "allowInsecure": false,
                  "fingerprint": "chrome"
                },
                "wsSettings": {
                  "path": "/",
                  "headers": { "Host": "$workerDomain" }
                },
                "sockopt": {
                  "dialerProxy": "fragment"
                }
              },
              "tag": "proxy"
            },
            {
              "protocol": "freedom",
              "tag": "fragment",
              "settings": {
                "fragment": {
                  "packets": "tlshello",
                  "length": "10-20",
                  "interval": "10-20"
                }
              }
            }
          ]
        }
        """.trimIndent()
    }

    @Synchronized
    private fun stopVpnInternal() {
        if (isStopping) return
        isStopping = true
        try {
            if (isHevRunning) HevSocks5Tunnel.hev_socks5_tunnel_stop()
            LibXray.stopXray()
            Thread.sleep(200)
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
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
        return NotificationCompat.Builder(this, "vpn")
            .setContentTitle("Заметки")
            .setContentText("Защита активна")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }
}
