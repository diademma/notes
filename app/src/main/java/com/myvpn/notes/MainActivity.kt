package com.myvpn.notes

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var uuidInput: EditText
    private lateinit var statusText: TextView
    private lateinit var btnToggle: Button
    private lateinit var logConsole: TextView
    private lateinit var logScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(40, 40, 40, 40)
        }

        val titleText = TextView(this).apply {
            text = "Заметки"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
        }

        uuidInput = EditText(this).apply {
            hint = "Введите предоставленный вам персональный id"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            val savedUuid = prefs.getString("user_uuid", "")
            if (!savedUuid.isNullOrEmpty()) {
                setText(savedUuid)
            }
        }

        statusText = TextView(this).apply {
            text = if (MyVpnService.isRunning) "Статус: Защищено" else "Статус: Отключено"
            textSize = 15f
            setTextColor(if (MyVpnService.isRunning) Color.GREEN else Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }

        btnToggle = Button(this).apply {
            text = if (MyVpnService.isRunning) "ВКЛ" else "ВЫКЛ"
            textSize = 18f
            setTextColor(Color.WHITE)
            
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (MyVpnService.isRunning) Color.parseColor("#4CAF50") else Color.parseColor("#333333"))
            }
            background = shape

            layoutParams = LinearLayout.LayoutParams(220, 220).apply {
                gravity = Gravity.CENTER
            }

            setOnClickListener {
                handleVpnToggle()
            }
        }

        // 📟 ТЕРМИНАЛ ДИАГНОСТИКИ И ЛОГОВ ОШИБОК В РЕАЛЬНОМ ВРЕМЕНИ
        logConsole = TextView(this).apply {
            text = "> Диагностический терминал готов...\n"
            textSize = 11f
            setTextColor(Color.GREEN)
            setPadding(20, 20, 20, 20)
        }

        logScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                400
            ).apply {
                topMargin = 40
            }
            addView(logConsole)
        }

        layout.addView(titleText)
        layout.addView(uuidInput)
        layout.addView(statusText)
        layout.addView(btnToggle)
        layout.addView(logScrollView)

        setContentView(layout)

        // Подключаем слушатель живых логов
        MyVpnService.onLogMessage = { logMsg ->
            runOnUiThread {
                logConsole.append("$logMsg\n")
                logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        checkNotificationPermission()
        requestTileAddition()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun requestTileAddition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            statusBarManager?.requestAddTileService(
                ComponentName(this, VpnTileService::class.java),
                "Заметки",
                Icon.createWithResource(this, android.R.drawable.ic_menu_edit),
                mainExecutor
            ) { }
        }
    }

    override fun onResume() {
        super.onResume()
        checkBluetoothOrExit()
    }

    private fun checkBluetoothOrExit() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(
                this,
                "Ошибка приложения: сбой инициализации модуля (Code: 0x80004005)",
                Toast.LENGTH_LONG
            ).show()
            finishAffinity()
        }
    }

    private fun handleVpnToggle() {
        val uuid = uuidInput.text.toString().trim()
        if (uuid.isEmpty()) {
            Toast.makeText(this, "Введите ваш персональный ID!", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("user_uuid", uuid)
            .apply()

        if (MyVpnService.isRunning) {
            val intent = Intent(this, MyVpnService::class.java).apply {
                action = "STOP"
            }
            startService(intent)
            updateUI(false)
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 100)
            } else {
                onActivityResult(100, Activity.RESULT_OK, null)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) {
            val intent = Intent(this, MyVpnService::class.java).apply {
                action = "START"
            }
            startService(intent)
            updateUI(true)
        }
    }

    private fun updateUI(running: Boolean) {
        statusText.text = if (running) "Статус: Защищено" else "Статус: Отключено"
        statusText.setTextColor(if (running) Color.GREEN else Color.GRAY)
        btnToggle.text = if (running) "ВКЛ" else "ВЫКЛ"
        
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) Color.parseColor("#4CAF50") else Color.parseColor("#333333"))
        }
        btnToggle.background = shape
    }
}
