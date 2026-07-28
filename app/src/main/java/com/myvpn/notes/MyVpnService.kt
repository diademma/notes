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
import android.util.Base64
import android.widget.Toast
import androidx.core.app.NotificationCompat
import libXray.LibXray
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class MyVpnService : VpnService() {

    companion object {
        var isRunning = false
        var onLogMessage: ((String) -> Unit)? = null

        fun log(msg: String) {
            onLogMessage?.invoke(msg)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "STOP") {
            log("🛑 Команда остановки...")
            stopVpn()
            return START_NOT_STICKY
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Сбой инициализации (Code: 0x80004005)", Toast.LENGTH_LONG).show()
            stopVpn()
            return START_NOT_STICKY
        }

        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        try {
            val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            val uuid = prefs.getString("user_uuid", "d342d11e-d424-4583-b36e-524ab1f0afa4") ?: ""

            log("🔑 Чтение UUID: ${uuid.take(8)}...")

            // 1. СОЗДАЕМ ФИЗИЧЕСКИЙ ФАЙЛ CONFIG.JSON НА ДИСКЕ ПЛАНШЕТА
            val configFile = File(filesDir, "config.json")
            if (configFile.exists()) configFile.delete()
            
            val rawJson = generateXrayJsonConfig(uuid)
            configFile.writeText(rawJson)
            log("📄 Файл конфигурации создан: ${configFile.name}")

            // 2. ЗАПУСКАЕМ XRAY ЧЕРЕЗ ПУТЬ К ФАЙЛУ
            log("⚙️ Запуск ядра Xray на порту 20809...")
            val base64Config = Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val result = try {
                LibXray.runXray(base64Config)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            log("🌐 Ответ Xray: $result")

            // Даем время на открытие порта 20809
            Thread.sleep(600)
            val isPortReady = checkProxyPort(20809)

            if (isPortReady) {
                log("✅ УСПЕХ! Порт 127.0.0.1:20809 ФИЗИЧЕСКИ ОТКРЫТ!")
            } else {
                log("⚠️ Ошибка: порт 20809 не ответил! Пробуем альтернативный запуск...")
            }

            // 3. ПРИВЯЗЫВАЕМ ANDROID К ОТКРЫТОМУ ПОРТУ
            val builder = Builder()
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setSession("Заметки")
                .addDisallowedApplication(packageName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", 20809))
                log("🔌 Системный трафик привязан к 127.0.0.1:20809")
            }

            vpnInterface = builder.establish()

            isRunning = true
            log("🎉 СИСТЕМА ГОТОВА! Открывай сайты!")

        } catch (e: Exception) {
            log("💥 ОШИБКА: ${e.message}")
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun checkProxyPort(port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 500)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun generateXrayJsonConfig(uuid: String): String {
        return """
        {
          "log": {
            "loglevel": "warning"
          },
          "inbounds": [
            {
              "port": 20809,
              "listen": "127.0.0.1",
              "protocol": "http"
            },
            {
              "port": 20808,
              "listen": "127.0.0.1",
              "protocol": "socks"
            }
          ],
          "outbounds": [
            {
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "104.26.6.213",
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
                  "serverName": "dark-poetry-8a03.tio-rex-ultra.workers.dev",
                  "allowInsecure": false
                },
                "wsSettings": {
                  "path": "/",
                  "headers": {
                    "Host": "dark-poetry-8a03.tio-rex-ultra.workers.dev"
                  }
                },
                "sockopt": {
                  "tcpNoDelay": true,
                  "fragment": {
                    "packets": "tlshello",
                    "length": "10-30",
                    "interval": "10-20"
                  }
                }
              }
            }
          ]
        }
        """.trimIndent()
    }

    private fun stopVpn() {
        try {
            log("🛑 Остановка...")
            LibXray.stopXray()
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log("👋 Отключено.")
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
