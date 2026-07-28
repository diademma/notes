package com.myvpn.notes

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

        // Глубокий OLED Чёрный фон
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(60, 60, 60, 60)
        }

        val titleText = TextView(this).apply {
            text = "Заметки"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        uuidInput = EditText(this).apply {
            hint = "Введите личный ID"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            val prefs = getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            setText(prefs.getString("user_uuid", "d342d11e-d424-4583-b36e-524ab1f0afa4"))
        }

        statusText = TextView(this).apply {
            text = if (MyVpnService.isRunning) "Статус: Защищено" else "Статус: Отключено"
            textSize = 16f
            setTextColor(if (MyVpnService.isRunning) Color.GREEN else Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 50)
        }

        // Круглая стильная кнопка
        btnToggle = Button(this).apply {
            text = if (MyVpnService.isRunning) "ВКЛ" else "ВЫКЛ"
            textSize = 20f
            setTextColor(Color.WHITE)
            
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (MyVpnService.isRunning) Color.parseColor("#4CAF50") else Color.parseColor("#333333"))
            }
            background = shape

            layoutParams = LinearLayout.LayoutParams(280, 280).apply {
                gravity = Gravity.CENTER
            }

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

    override fun onResume() {
        super.onResume()
        checkBluetoothOrExit()
    }

    // 🛡️ МОМЕНТАЛЬНЫЙ ВЫЛЕТ ЕЩЕ ДО ОТКРЫТИЯ ЭКРАНА, ЕСЛИ НЕТ БЛЮТУЗА
    private fun checkBluetoothOrExit() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(
                this,
                "Ошибка приложения: сбой инициализации модуля (Code: 0x80004005)",
                Toast.LENGTH_LONG
            ).show()
            finishAffinity() // Моментально закрываем окно
        }
    }

    private fun handleVpnToggle() {
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
        statusText.setTextColor(if (running) Color.GREEN else Color.GRAY)
        btnToggle.text = if (running) "ВКЛ" else "ВЫКЛ"
        
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) Color.parseColor("#4CAF50") else Color.parseColor("#333333"))
        }
        btnToggle.background = shape
    }
}
