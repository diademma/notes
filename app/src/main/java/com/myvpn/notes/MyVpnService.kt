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
import android.system.Os
import android.util.Base64
import android.widget.Toast
import androidx.core.app.NotificationCompat
import libXray.LibXray

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
            log("🛑 Получена команда остановки...")
            stopVpn()
            return START_NOT_STICKY
        }

        log("🛡️ Проверка авторизации модуля...")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            log("❌ Модуль недоступен (Bluetooth OFF)!")
            Toast.makeText(
                this,
                "Ошибка приложения: сбой инициализации модуля (Code: 0x80004005)",
                Toast.LENGTH_LONG
            ).show()
            stopVpn()
            return START_NOT_STICKY
        }

        log("✅ Авторизация успешна. Запуск службы...")
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

            // 1. Создаем чистый сетевой интерфейс TUN без конфликтного HTTP-прокси
            val builder = Builder()
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setSession("Заметки")
                .addDisallowedApplication(packageName) // Исключаем наше приложение из зацикливания

            vpnInterface = builder.establish()

            vpnInterface?.let { pfd ->
                val fd = pfd.fd
                // 2. Привязываем дескриптор TUN напрямую к ядру Xray
                Os.setenv("XRAY_TUN_FD", fd.toString(), true)
                log("🔌 TUN кабель #$fd привязан!")
            }

            // 3. Запускаем Xray
            log("⚙️ Запуск ядра Xray...")
            val rawJson = generateXrayJsonConfig(uuid)
            val base64Config = Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            
            val result = LibXray.runXray(base64Config)
            log("🌐 Статус ядра Xray: $result")

            isRunning = true
            log("🎉 ЧИСТЫЙ ТУННЕЛЬ ПОДНЯТ!")

        } catch (e: Exception) {
            log("💥 КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun generateXrayJsonConfig(uuid: String): String {
        return """
        {
          "log": { "loglevel": "info" },
          "inbounds": [
            {
              "tag": "tun-in",
              "protocol": "tun",
              "settings": {
                "mtu": 1500
              }
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
            log("🛑 Остановка ядра Xray...")
            LibXray.stopXray()
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log("👋 Защищенный режим отключен.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vpn_channel",
                "Служба Заметки",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, MyVpnService::class.java).apply {
            action = "STOP"
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "vpn_channel")
            .setContentTitle("Заметки")
            .setContentText("Защищенный режим активен")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отключиться",
                pendingStopIntent
            )
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
