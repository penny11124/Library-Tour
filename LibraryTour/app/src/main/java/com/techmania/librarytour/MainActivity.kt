package com.techmania.librarytour

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "LibraryTourApp"

    // --- UI 元件 ---
    private lateinit var imageView: ImageView
    private lateinit var infoTextView: TextView
    private lateinit var chineseTourButton: Button
    private lateinit var englishTourButton: Button

    // --- BLE ---
    private lateinit var bleScanner: BluetoothLeScanner
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val scanPeriod: Long = 8000
    private val scanInterval: Long = 4000

    data class BeaconInfo(
        val rssiHistory: MutableList<Int> = mutableListOf(),
        var lastSeenTimestamp: Long = 0L
    )
    private val beaconDataMap = mutableMapOf<String, BeaconInfo>()
    private val RSSI_HISTORY_SIZE = 5
    private val STALE_BEACON_TIMEOUT = scanInterval + 2000L

    // --- 狀態管理 ---
    private var lastKnownArea: String? = null
    private var mediaPlayer: MediaPlayer? = null


    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allPermissionsGranted = permissions.entries.all { it.value }
            if (allPermissionsGranted) {
                if (isBluetoothEnabled()) {
                    Log.i(TAG, "所有權限已授予，準備開始掃描...")
                    startBLEScan()
                } else {
                    Log.w(TAG, "藍牙未啟用。")
                    Toast.makeText(this, "請手動啟用藍牙後重啟 App", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.e(TAG, "部分或所有權限被拒絕。")
                Toast.makeText(this, "需要藍牙相關權限才能掃描裝置", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.areaImageView)
        infoTextView = findViewById(R.id.infoTextView)
        chineseTourButton = findViewById(R.id.ChineseTour)
        englishTourButton = findViewById(R.id.EnglishTour)

        setTourButtonsEnabled(false)

        chineseTourButton.setOnClickListener {
            playTourAudio(getAudioResourceForArea(lastKnownArea, "zh"))
        }

        englishTourButton.setOnClickListener {
            playTourAudio(getAudioResourceForArea(lastKnownArea, "en"))
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "此裝置不支援藍牙")
            Toast.makeText(this, "此裝置不支援藍牙", Toast.LENGTH_LONG).show()
            return
        }

        checkAndRequestPermissions()
    }

    private fun playTourAudio(audioResId: Int?) {
        if (audioResId == null) {
            Log.w(TAG, "沒有可用的語音資源")
            return
        }
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, audioResId)
        mediaPlayer?.start()
    }

    private fun getAudioResourceForArea(area: String?, lang: String): Int? {
        return when (area) {
            "A" -> if (lang == "zh") R.raw.audio_a else R.raw.audio_a_en
            "B" -> if (lang == "zh") R.raw.audio_b else R.raw.audio_b_en
            "C" -> if (lang == "zh") R.raw.audio_c else R.raw.audio_c_en
            else -> null
        }
    }

    private fun updateGuideContent(area: String?) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        when (area) {
            "A" -> {
                imageView.setImageResource(R.drawable.area_a)
                infoTextView.text = "您現在位於:\n知識集 新知,主題展\nThe Circle, Exhibition"
                setTourButtonsEnabled(true)
            }
            "B" -> {
                imageView.setImageResource(R.drawable.area_b)
                infoTextView.text = "您現在位於:\n清沙龍\nIdea Hub"
                setTourButtonsEnabled(true)
            }
            "C" -> {
                imageView.setImageResource(R.drawable.area_c)
                infoTextView.text = "您現在位於:\n輕學區\nLight Up Zone"
                setTourButtonsEnabled(true)
            }
            else -> {
                imageView.setImageResource(0)
                infoTextView.text = "歡迎使用圖書館導覽，請靠近導覽點"
                setTourButtonsEnabled(false)
            }
        }
    }

    private fun setTourButtonsEnabled(isEnabled: Boolean) {
        chineseTourButton.isEnabled = isEnabled
        englishTourButton.isEnabled = isEnabled
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "正在請求權限: ${permissionsToRequest.joinToString()}")
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            if (isBluetoothEnabled()) startBLEScan() else Log.w(TAG, "藍牙未啟用。")
        }
    }

    private fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    private fun startBLEScan() {
        if (isScanning || !isBluetoothEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return

        bleScanner = bluetoothAdapter!!.bluetoothLeScanner
        Log.d(TAG, "===== 開始掃描 =====")

        handler.postDelayed({
            if (isScanning) {
                isScanning = false
                bleScanner.stopScan(leScanCallback)
                Log.d(TAG, "===== 掃描結束 =====")
                handler.postDelayed({ startBLEScan() }, scanInterval)
            }
        }, scanPeriod)

        isScanning = true
        bleScanner.startScan(leScanCallback)
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

            val name = result.device.name ?: "Unnamed"
            val rssi = result.rssi

            if (name in listOf("ESP32_LibA", "ESP32_LibB", "ESP32_LibC")) {
                // --- 修改點：更新 BeaconInfo，包含時間戳 ---
                val beaconInfo = beaconDataMap.getOrPut(name) { BeaconInfo() }
                beaconInfo.lastSeenTimestamp = System.currentTimeMillis()
                beaconInfo.rssiHistory.add(rssi)
                if (beaconInfo.rssiHistory.size > RSSI_HISTORY_SIZE) {
                    beaconInfo.rssiHistory.removeAt(0)
                }
            }

            val now = System.currentTimeMillis()
            val freshBeacons = beaconDataMap.filter {
                now - it.value.lastSeenTimestamp < STALE_BEACON_TIMEOUT
            }

            if (freshBeacons.isEmpty() && lastKnownArea != null) {
                Log.d(TAG, "所有Beacon已消失，重設 UI")
                lastKnownArea = null
                runOnUiThread { updateGuideContent(null) }
                return
            }

            // 計算新鮮 Beacon 的平均 RSSI
            val averageRssiMap = freshBeacons.mapValues { it.value.rssiHistory.average().toInt() }
            val strongestBeacon = averageRssiMap.maxByOrNull { it.value }?.key

            val currentArea = when (strongestBeacon) {
                "ESP32_LibA" -> "A"
                "ESP32_LibB" -> "B"
                "ESP32_LibC" -> "C"
                else -> null
            }

            if (currentArea != lastKnownArea) {
                Log.d(TAG, "區域變化: 從 $lastKnownArea 到 $currentArea")
                lastKnownArea = currentArea
                runOnUiThread { updateGuideContent(currentArea) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "掃描失敗，錯誤碼: $errorCode")
            isScanning = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (isScanning) {
            Log.i(TAG, "App 進入背景，停止掃描。")
            isScanning = false
            handler.removeCallbacksAndMessages(null)
            if (::bleScanner.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bleScanner.stopScan(leScanCallback)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isScanning) {
            Log.i(TAG, "App 返回前景，準備重新開始掃描。")
            checkAndRequestPermissions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
