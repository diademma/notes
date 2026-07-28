package com.myvpn.notes

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        if (MyVpnService.isRunning) {
            val intent = Intent(this, MyVpnService::class.java).apply {
                action = "STOP"
            }
            startService(intent)
            updateTileState()
        } else {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                Toast.makeText(
                    this,
                    "Ошибка приложения: сбой инициализации модуля (Code: 0x80004005)",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent == null) {
                val intent = Intent(this, MyVpnService::class.java).apply {
                    action = "START"
                }
                startService(intent)
                updateTileState()
            } else {
                Toast.makeText(this, "Откройте Заметки для подтверждения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (MyVpnService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Заметки (ВКЛ)"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Заметки"
        }
        tile.updateTile()
    }
}
