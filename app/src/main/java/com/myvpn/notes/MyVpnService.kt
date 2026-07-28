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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.nec.sopy.singbox.BoxService
import io.nec.sopy.singbox.CommandServer

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

            // 1. Создаем сетевой интерфейс
            val builder = Builder()
                .addAddress("172.19.0.1", 30)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .setMtu(1500)
                .setSession("Заметки")
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()

            // 2. Запускаем ультралегкий движок Sing-Box
            log("⚙️ Запуск легкого C++ ядра Sing-Box...")
            val configJson = generateSingBoxJsonConfig(uuid)
            
            // Старт высокоскоростного туннеля
            val pfd = vpnInterface
            if (pfd != null) {
                BoxService.start(configJson, pfd.fd)
                log("🔌 Стек LWIP запущен! Подключение к 104.26.6.213...")
            }

            isRunning = true
            log("🎉 ГОТОВО! 6-Мегабайтный десант готов к работе!")

        } catch (e: Exception) {
            log("💥 КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun generateSingBoxJsonConfig(uuid: String): String {
        return """
        {
          "log": { "level": "panic" },
          "inbounds": [
            {
              "type": "tun",
              "tag": "tun-in",
              "inet4_address": "172.19.0.1/30",
              "inet6_address": "fdfe:dcba:9876::1/126",
              "mtu": 1500,
              "auto_route": true,
              "strict_route": true,
              "stack": "lwip",
              "sniff": true
            }
          ],
          "outbounds": [
            {
              "type": "vless",
              "tag": "proxy",
              "server": "104.26.6.213",
              "server_port": 443,
              "uuid": "$uuid",
              "transport": {
                "type": "ws",
                "path": "/",
                "headers": {
                  "Host": "dark-poetry-8a03.tio-rex-ultra.workers.dev"
                }
              },
              "tls": {
                "enabled": true,
                "server_name": "dark-poetry-8a03.tio-rex-ultra.workers.dev",
                "insecure": false,
                "fragment": {
                  "enabled": true,
                  "size": "10-30",
                  "sleep": "10-20"
                }
              }
            }
          ]
        }
        """.trimIndent()
    }

    private fun stopVpn() {
        try {
            log("🛑 Остановка ядра...")
            BoxService.stop()
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log("👋 Защищенный режим отключен.")
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
