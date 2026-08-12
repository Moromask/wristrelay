package com.wristrelay.app.qr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.wristrelay.app.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Экран сканирования QR-кода с экрана часов.
 * Формат QR: WRISTRELAY:1:<hex nonce> (см. docs/PROTOCOL.md).
 *
 * Результат возвращается через companion.consumeResult() после finish().
 */
class QrScannerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QrScanner"

        /** Формат: "WRISTRELAY:1:<64 hex>" */
        private const val PREFIX = "WRISTRELAY:1:"
        private const val NONCE_HEX_LEN = 64

        private var result: ByteArray? = null

        /** Результат сканирования (null, если отменено/не распознано). */
        fun consumeResult(): ByteArray? {
            val r = result
            result = null
            return r
        }

        fun parseNonce(raw: String): ByteArray? {
            if (!raw.startsWith(PREFIX)) return null
            val hex = raw.substring(PREFIX.length)
            if (hex.length != NONCE_HEX_LEN) return null
            return try {
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContentView(previewView)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasCamera) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            scanFrame(imageProxy)
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "camera bind failed: ${e.message}")
            finish()
        }
    }

    private fun scanFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        val scanner = BarcodeScanning.getClient()

        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.format != Barcode.FORMAT_QR_CODE) continue
                    val raw = barcode.rawValue ?: continue
                    val nonce = parseNonce(raw)
                    if (nonce != null) {
                        result = nonce
                        Log.d(TAG, "QR распознан: nonce len=${nonce.size}")
                        runOnUiThread { finish() }
                        break
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
