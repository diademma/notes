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

        // 1. Обязательный запуск Foreground сразу в главном потоке для Android 14+
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

        // 2. Вся тяжелая фоновая инициализация выполняется в отдельном потоке
        thread {
            startVpnInternal()
        }

        return START_STICKY
    }

    private fun startVpnInternal() {
        if (isRunning) return
        isStopping = false

        try {
            val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            val uuid = prefs.getString("user_uuid", "d8116f0f-5ad5-4f07-b63b-0877a3113ca2") ?: ""

            log("🔑 Чтение UUID: ${uuid.take(8)}...")

            // СОЗДАЕМ МИКРО GEO-ФАЙЛЫ И УКАЗЫВАЕМ ПУТЬ ДЛЯ XRAY
            val geoip = File(filesDir, "geoip.dat")
            if (!geoip.exists()) geoip.createNewFile()
            val geosite = File(filesDir, "geosite.dat")
            if (!geosite.exists()) geosite.createNewFile()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    android.system.Os.setenv("XRAY_LOCATION_ASSET", filesDir.absolutePath, true)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }

            // Очищаем прошлый запуск ядра при перезапуске
            try { LibXray.stopXray() } catch (e: Exception) {}

            // 1. ЗАПУСКАЕМ XRAY НА ПОРТУ 10808 (SOCKS5)
            log("⚙️ Запуск ядра Xray...")
            val rawJson = generateXrayJsonConfig(uuid)
            val base64Config = Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val result = LibXray.runXray(base64Config)
            log("ℹ️ Статус Xray: $result")

            // Ждем готовности порта 10808
            var attempts = 0
            while (!checkProxyPort(10808) && attempts < 15) {
                Thread.sleep(200)
                attempts++
            }

            if (!checkProxyPort(10808)) {
                log("❌ Xray не успел открыть порт 10808!")
                stopVpnInternal()
                return
            }

            log("✅ Порт 10808 готов!")

            // 2. СОЗДАЕМ СЕТЕВОЙ ИНТЕРФЕЙС TUN С ИСКЛЮЧЕНИЕМ НАШЕГО ПРИЛОЖЕНИЯ
            val builder = Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setSession("Заметки")

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                log("⚠️ Исключение пакета: ${e.message}")
            }

            vpnInterface = builder.establish()

            val pfd = vpnInterface
            if (pfd == null) {
                log("❌ Сбой получения TUN кабеля!")
                stopVpnInternal()
                return
            }

            val tunFd = pfd.fd
            log("✅ TUN кабельный порт: $tunFd")

            // 3. СОЗДАЕМ КОНФИГ ДЛЯ HEV-SOCKS5
            val ymlFile = File(filesDir, "socks.yml")
            if (ymlFile.exists()) ymlFile.delete()
            ymlFile.writeText(generateHevConfig())

            // 4. ЗАПУСКАЕМ C++ МОДУЛЬ ТУННЕЛИРОВАНИЯ
            log("🔌 Запуск C++ двигателя (hev-socks5)...")
            isRunning = true
            isHevRunning = true

            thread(name = "HevSocks5Thread") {
                try {
                    HevSocks5Tunnel.hev_socks5_tunnel_main(ymlFile.absolutePath, tunFd)
                } catch (e: Throwable) {
                    log("⚠️ C++ поток завершен: ${e.message}")
                } finally {
                    isHevRunning = false
                }
            }

            log("🎉 ПОБЕДА! Туннель поднят!")

        } catch (e: Exception) {
            log("💥 ОШИБКА: ${e.message}")
            e.printStackTrace()
            stopVpnInternal()
        }
    }

    private fun checkProxyPort(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun generateHevConfig(): String {
        return """
        tunnel:
          mtu: 1500

        socks5:
          port: 10808
          address: 127.0.0.1
          udp: 'udp'

        misc:
          task-stack-size: 8192
        """.trimIndent()
    }

    private fun generateXrayJsonConfig(uuid: String): String {
        val domain = "dark-poetry-8a03.tio-rex-ultra.workers.dev"

        return """
        {
          "log": {
            "loglevel": "warning"
          },
          "inbounds": [
            {
              "port": 10808,
              "listen": "127.0.0.1",
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              }
            }
          ],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "$domain",
                    "port": 443,
                    "users": [
                      {
                        "id": "$uuid",
                        "encryption": "none"
                      }
                    ]
                  }
                ]
              },
              "streamSettings": {
                "network": "ws",
                "security": "tls",
                "tlsSettings": {
                  "serverName": "$domain",
                  "allowInsecure": false,
                  "fingerprint": "chrome",
                  "alpn": [
                    "http/1.1"
                  ]
                },
                "wsSettings": {
                  "path": "/",
                  "headers": {
                    "Host": "$domain"
                  }
                },
                "sockopt": {
                  "dialerProxy": "fragment",
                  "tcpNoDelay": true
                }
              }
            },
            {
              "tag": "fragment",
              "protocol": "freedom",
              "settings": {
                "fragment": {
                  "packets": "tlshello",
                  "length": "10-40",
                  "interval": "10-20"
                }
              },
              "streamSettings": {
                "sockopt": {
                  "tcpNoDelay": true
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
        log("🛑 Остановка...")

        try {
            // Остановка C++ туннеля только если он был запущен
            if (isHevRunning) {
                try { HevSocks5Tunnel.hev_socks5_tunnel_stop() } catch (e: Throwable) {}
                isHevRunning = false
            }

            // Остановка Xray
            try { LibXray.stopXray() } catch (e: Throwable) {}

            Thread.sleep(100)

            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            log("👋 Отключено.")
        }
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("vpn_channel", "Служба Заметки", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, MyVpnService::class.java).apply { action = "STOP" }
        val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "vpn_channel")
            .setContentTitle("Заметки")
            .setContentText("Защищенный режим активен")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключиться", pendingStopIntent)
            .build()
    }
}
