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
import java.io.File
import java.io.RandomAccessFile
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

            // 1. Создаем файл для ЖИВЫХ ЛОГОВ!
            val logFile = File(filesDir, "xray_error.log")
            if (logFile.exists()) logFile.delete()
            logFile.createNewFile()

            // 2. БРОНЕБОЙНЫЙ ПЕРЕХВАТ ТРАФИКА
            val builder = Builder()
                .addAddress("10.0.0.2", 32)
                .addAddress("fd00::2", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1") // Все запросы полетят на 1.1.1.1 ЧЕРЕЗ ТУННЕЛЬ
                .setMtu(1500)
                .setSession("Заметки")
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()

            vpnInterface?.let { pfd ->
                val fd = pfd.fd
                Os.setenv("XRAY_TUN_FD", fd.toString(), true)
                log("🔌 TUN кабель #$fd привязан!")
            }

            // 3. ЗАПУСКАЕМ ЖИВОЙ ЧИТАТЕЛЬ ЛОГОВ (ВЕРНУЛ ЕГО НА МЕСТО!)
            isTunnelActive = true
            startLiveLogTailer(logFile)

            // 4. ЗАПУСКАЕМ XRAY В РЕЖИМЕ DEBUG
            log("⚙️ Запуск ядра Xray (режим DEBUG)...")
            val rawJson = generateXrayJsonConfig(uuid, logFile.absolutePath)
            val base64Config = Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            
            val result = LibXray.runXray(base64Config)
            log("🌐 Инициализация Xray: $result")

            isRunning = true

        } catch (e: Exception) {
            log("💥 КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            e.printStackTrace()
            stopVpn()
        }
    }

    // 📟 ВОТ ОН - СТРИМЕР ЖИВЫХ ЛОГОВ, ЧТОБЫ МЫ ВИДЕЛИ КАЖДЫЙ ПАКЕТ
    private fun startLiveLogTailer(logFile: File) {
        thread {
            try {
                var lastPointer = 0L
                while (isTunnelActive) {
                    if (logFile.exists() && logFile.length() > lastPointer) {
                        val raf = RandomAccessFile(logFile, "r")
                        raf.seek(lastPointer)
                        var line = raf.readLine()
                        while (line != null) {
                            if (line.isNotBlank()) {
                                // Исправляем кодировку логов
                                val utf8Line = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                                log("📜 $utf8Line")
                            }
                            line = raf.readLine()
                        }
                        lastPointer = raf.filePointer
                        raf.close()
                    }
                    Thread.sleep(300)
                }
            } catch (e: Exception) {}
        }
    }

    // 🛠 ТУПАЯ И НАДЕЖНАЯ МАРШРУТИЗАЦИЯ (Всё в Воркер!)
    private fun generateXrayJsonConfig(uuid: String, logPath: String): String {
        return """
        {
          "log": {
            "loglevel": "debug",
            "error": "$logPath"
          },
          "inbounds": [
            {
              "tag": "tun-in",
              "protocol": "tun",
              "settings": {
                "mtu": 1500,
                "autoProxy": true
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
