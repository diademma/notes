package com.myvpn.notes

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var uuidInput: EditText
    private lateinit var statusText: TextView
    private lateinit var btnToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Минималистичная верстка без единого xml-файла!
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }

        val titleText = TextView(this).apply {
            text = "Заметки"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        uuidInput = EditText(this).apply {
            hint = "Введите ваш личный ID"
            textSize = 14f
            val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            setText(prefs.getString("user_uuid", "d342d11e-d424-4583-b36e-524ab1f0afa4"))
        }

        statusText = TextView(this).apply {
            text = if (MyVpnService.isRunning) "Статус: Защищено" else "Статус: Отключено"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
        }

        btnToggle = Button(this).apply {
            text = if (MyVpnService.isRunning) "ОСТАНОВИТЬ" else "ПОДКЛЮЧИТЬСЯ"
            textSize = 18f
            setOnClickListener {
                handleVpnToggle()
            }
        }

        layout.addView(titleText)
        layout.addView(uuidInput)
        layout.addView(statusText)
        layout.addView(btnToggle)

        setContentView(layout)
    }

    private fun handleVpnToggle() {
        // 🛡️ СЕКРЕТНАЯ ЗАЩИТА ОТ ДЕТЕЙ: Проверка Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(
                this,
                "Ошибка приложения: сбой инициализации модуля (Code: 0x80004005)",
                Toast.LENGTH_LONG
            ).show()
            finishAffinity() // Закрываем приложение
            return
        }

        // Если Bluetooth включен — сохраняем UUID и запускаем VPN
        val uuid = uuidInput.text.toString().trim()
        if (uuid.isEmpty()) {
            Toast.makeText(this, "Введите ваш ID!", Toast.LENGTH_SHORT).show()
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
        btnToggle.text = if (running) "ОСТАНОВИТЬ" else "ПОДКЛЮЧИТЬСЯ"
    }
}
