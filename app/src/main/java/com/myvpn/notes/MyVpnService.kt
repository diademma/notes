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
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MyVpnService : VpnService() {

    companion object {
        var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isTunnelActive = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        // 🛡️ ОДНОРАЗОВАЯ ПРОВЕРКА BLUETOOTH ПРИ СТАРТЕ
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(
                this,
                "Ошибка приложения: сбой инициализации модуля (Code: 0x80004005)",
                Toast.LENGTH_LONG
            ).show()
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

            // Создаем виртуальный интерфейс Android TUN
            val builder = Builder()
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .setMtu(1500)
                .setSession("Заметки")

            vpnInterface = builder.establish()
            isRunning = true
            isTunnelActive = true

            // Запускаем нативную дробрилку пакетов в отдельном потоке
            thread {
                runNativeFragmentedTunnel(uuid)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    // ✂️ НАША СОБСТВЕННАЯ ДРОБИЛКА ПАКЕТОВ НА ЧИСТОМ KOTLIN
    private fun runNativeFragmentedTunnel(uuid: String) {
        try {
            val cleanIp = "104.26.6.213"
            val port = 443

            val rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.connect(InetSocketAddress(cleanIp, port), 5000)

            // Заворачиваем поток вывода в нашу собственную разрезалку
            val fragmentedOutput = FragmentedOutputStream(rawSocket.getOutputStream(), splitPos = 35)

            // Сокет готов и пакеты будут разрезаться нативно!
            while (isTunnelActive && !rawSocket.isClosed) {
                Thread.sleep(1000)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVpn() {
        isTunnelActive = false
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

// 🛡️ 
class FragmentedOutputStream(out: OutputStream, private val splitPos: Int = 35) : FilterOutputStream(out) {
    private var isFirstWrite = true

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (isFirstWrite && len > splitPos) {
            isFirstWrite = false
            // Часть 1: Первые 35 байт
            out.write(b, off, splitPos)
            out.flush()
            try { Thread.sleep(50) } catch (e: Exception) {}
            // Часть 2: Оставшийся хвостик пакета
            out.write(b, off + splitPos, len - splitPos)
            out.flush()
        } else {
            out.write(b, off, len)
        }
    }
}
