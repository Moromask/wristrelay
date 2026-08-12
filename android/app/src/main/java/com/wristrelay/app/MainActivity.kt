package com.wristrelay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wristrelay.app.qr.QrScannerActivity
import com.wristrelay.app.service.BridgeService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BridgeHome()
                }
            }
        }
    }
}

@Composable
private fun BridgeHome() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var running by remember { mutableStateOf(false) }

    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    // Флаг: запустить службу сразу после выдачи разрешений
    var pendingStart by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (pendingStart && !running) {
            BridgeService.start(context)
            running = true
        }
        pendingStart = false
    }

    // Запуск сканера QR и обработка результата (nonce с экрана часов)
    val qrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val nonce = QrScannerActivity.consumeResult()
        if (nonce != null) {
            val delivered = BridgeService.submitQrNonce(nonce)
            Toast.makeText(
                context,
                if (delivered) "QR принят: ожидаем подключения часов…" else "Запустите службу перед сканированием",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(text = "WristRelay", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (running) "Статус: служба запущена" else "Статус: служба остановлена",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "BLE-реклама активна — часы видят телефон как устройство WristRelay.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Button(
            onClick = {
                if (running) {
                    BridgeService.stop(context)
                    running = false
                } else {
                    pendingStart = true
                    launcher.launch(permissions)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "Остановить синхронизацию" else "Запустить синхронизацию")
        }

        OutlinedButton(
            onClick = {
                if (!running) {
                    pendingStart = true
                    launcher.launch(permissions)
                }
                qrLauncher.launch(Intent(context, QrScannerActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сканировать QR-код часов")
        }
    }
}
