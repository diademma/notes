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
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

class MyVpnService : VpnService() {

    companion object {
        var isRunning = false
        var onLogMessage: ((String) -> Unit)? = null

        fun log(msg: String) {
            onLogMessage?.invoke(msg)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isTunnelActive = false

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

            // 1. Создаем правильный сетевой интерфейс TUN
            val builder = Builder()
                .addAddress("10.0.0.2", 24)
                .addAddress("fd00::2", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setSession("Заметки")
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()

            vpnInterface?.let { pfd ->
                val fd = pfd.fd
                Os.setenv("XRAY_TUN_FD", fd.toString(), true)
                log("🔌 TUN кабель #$fd привязан!")
            }

            // 2. ЧИТАЕМ СИСТЕМНЫЙ LOGCAT В РЕАЛЬНОМ ВРЕМЕНИ!
            isTunnelActive = true
            startLogcatReader()

            // 3. ЗАПУСКАЕМ XRAY С СЕТЕВЫМ СТЕКОМ GVISOR
            log("⚙️ Запуск ядра Xray (стек gVisor)...")
            val rawJson = generateXrayJsonConfig(uuid)
            val base64Config = Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            
            val result = LibXray.runXray(base64Config)
            log("🌐 Ответ ядра Xray: $result")

            isRunning = true
            log("🎉 СТЕК GVISOR ВКЛЮЧЕН! Проверяем интернет!")

        } catch (e: Exception) {
            log("💥 КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            e.printStackTrace()
            stopVpn()
        }
    }

    // 📟 ПРЯМОЙ ЧИТАТЕЛЬ LOGCAT ЖУРНАЛА ANDROID
    private fun startLogcatReader() {
        thread {
            try {
                // Очищаем прошлый логcat
                Runtime.getRuntime().exec("logcat -c")
                
                val process = Runtime.getRuntime().exec("logcat -v time")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String? = reader.readLine()
                
                while (isTunnelActive && line != null) {
                    if (line.contains("Xray") || line.contains("libXray") || line.contains("app/proxy")) {
                        log("📜 $line")
                    }
                    line = reader.readLine()
                }
            } catch (e: Exception) {}
        }
    }

    // 🛠 КОНФИГУРАЦИЯ С ОБОЗНАЧЕНИЕМ "stack": "gvisor"
    private fun generateXrayJsonConfig(uuid: String): String {
        return """
        {
          "log": {
            "loglevel": "debug"
          },
          "inbounds": [
            {
              "tag": "tun-in",
              "protocol": "tun",
              "settings": {
                "mtu": 1500,
                "stack": "gvisor"
              },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic"]
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
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              {
                "type": "field",
                "inboundTag": ["tun-in"],
                "outboundTag": "proxy"
              }
            ]
          }
        }
        """.trimIndent()
    }

    private fun stopVpn() {
        isTunnelActive = false
        try {
            log("🛑 Остановка ядра Xray...")
            LibXray.stopXray()
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
