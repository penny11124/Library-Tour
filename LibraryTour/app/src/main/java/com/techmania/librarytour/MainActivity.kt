package com.techmania.librarytour

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), AreaChangeListener{

    private val TAG = "LibraryTourApp"

    // --- UI 元件 ---
    private lateinit var imageView: ImageView
    private lateinit var infoTextView: TextView
    private lateinit var chineseTourButton: Button
    private lateinit var englishTourButton: Button
    private lateinit var mapButton: Button

    // --- 狀態管理 ---
    private var mediaPlayer: MediaPlayer? = null
    private var currentDisplayedArea: String? = null // Use this to track what's currently shown/played
    private var currentAudioResId: Int? = null

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allPermissionsGranted = permissions.entries.all { it.value }
            if (allPermissionsGranted) {
                if (BleScannerManager.isBluetoothEnabled()) {
                    Log.i(TAG, "所有權限已授予，準備開始掃描...")
                    BleScannerManager.startScan()
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

        BleScannerManager.initialize(applicationContext)

        imageView = findViewById(R.id.areaImageView)
        infoTextView = findViewById(R.id.infoTextView)
        chineseTourButton = findViewById(R.id.ChineseTour)
        englishTourButton = findViewById(R.id.EnglishTour)
        mapButton = findViewById(R.id.mapButton)

        setTourButtonsEnabled(false)
        setMapButtonEnabled(false)

        chineseTourButton.setOnClickListener {
            playTourAudio(getAudioResourceForArea(currentDisplayedArea, "zh"))
        }

        englishTourButton.setOnClickListener {
            playTourAudio(getAudioResourceForArea(currentDisplayedArea, "en"))
        }

        mapButton.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java).apply{}
            startActivity(intent)
        }

        checkAndRequestPermissions()
    }

    // --- AreaChangeListener Implementation ---
    override fun onAreaChanged(newArea: String?) {
        runOnUiThread { // Ensure UI updates are on the main thread
            // 無論 currentDisplayedArea 是否與 newArea 相同，只要是 AreaChanged，
            // 就代表有 Beacon 重新計算區域。語音的處理應該與 UI 顯示分開考慮。
            // 我們希望在任何區域變化時，如果正在播放語音，就停止它。

            // 只有當區域真的不同時才更新 UI 和播放新的語音
            if (newArea != currentDisplayedArea) {
                currentDisplayedArea = newArea
                updateGuideContent(currentDisplayedArea) // 更新 UI 和準備語音播放
            } else {
                // 如果區域沒有變化，但可能之前有語音在播放，且不希望它繼續播放
                // 或者只是為了確保當區域穩定下來時語音也會停止，
                // 這取決於你的語音播放邏輯 (是自動播放還是點擊播放)
                // 如果語音只在點擊按鈕後播放，那麼這裡不需要停止。
                // 如果語音是自動播放的，則需要考慮在區域穩定後停止。
                // For now, let's assume auto-play on area change
                // 如果您是點擊按鈕才播放，這裡就不需要再停了，因為 onAreaChanged 已經觸發了 updateGuideContent 的停止邏輯。
                // 由於您的語音是點擊按鈕播放，所以這裡不需要額外的停止邏輯。
                // updateGuideContent 本身會在開頭停止現有語音。
            }
        }
    }

    private fun playTourAudio(audioResId: Int?) {
        if (audioResId == null) {
            Log.w(TAG, "沒有可用的語音資源")
            return
        }
        mediaPlayer?.stop() // 停止任何現有語音
        mediaPlayer?.release() // 釋放資源
        mediaPlayer = null // 清空
        mediaPlayer = MediaPlayer.create(this, audioResId)
        mediaPlayer?.start()
        currentAudioResId = audioResId // 記錄當前播放的音頻ID
    }

    private fun getAudioResourceForArea(area: String?, lang: String): Int? {
        return when (area) {
            "D" -> if (lang == "zh") R.raw.audio_a else R.raw.audio_a_en
            "F" -> if (lang == "zh") R.raw.audio_b else R.raw.audio_b_en
            "E" -> if (lang == "zh") R.raw.audio_c else R.raw.audio_c_en
            else -> null
        }
    }

    private fun updateGuideContent(area: String?) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentAudioResId = null
        when (area) {
            "D" -> {
                imageView.setImageResource(R.drawable.area_a)
                infoTextView.text = "您現在位於:\n知識集 新知,主題展\nThe Circle, Exhibition"
                setTourButtonsEnabled(true)
                setMapButtonEnabled(true)
            }
            "E" -> {
                imageView.setImageResource(R.drawable.area_c)
                infoTextView.text = "您現在位於:\n輕學區\nLight Up Zone"
                setTourButtonsEnabled(true)
                setMapButtonEnabled(true)
            }
            "F" -> {
                imageView.setImageResource(R.drawable.area_b)
                infoTextView.text = "您現在位於:\n清沙龍\nIdea Hub"
                setTourButtonsEnabled(true)
                setMapButtonEnabled(true)
            }
            else -> {
                imageView.setImageResource(0)
                infoTextView.text = "歡迎使用圖書館導覽，請靠近導覽點"
                setTourButtonsEnabled(false)
                setMapButtonEnabled(false)
            }
        }
    }

    private fun setTourButtonsEnabled(isEnabled: Boolean) {
        chineseTourButton.isEnabled = isEnabled
        englishTourButton.isEnabled = isEnabled
    }

    private fun setMapButtonEnabled(isEnabled: Boolean) {
        mapButton.isEnabled = isEnabled
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
            if (BleScannerManager.isBluetoothEnabled()) {
                BleScannerManager.startScan()
            } else {
                Log.w(TAG, "藍牙未啟用。")
            }
        }
    }

    private fun checkBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister MainActivity as an AreaChangeListener when it goes to background
        Log.i(TAG, "App進入背景，停止掃描。")
        BleScannerManager.removeAreaChangeListener(this)

        // !!! 在 Activity 進入背景時，主動停止語音播放
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        Log.i(TAG, "語音播放已停止，因為 MainActivity 進入背景。")
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "App返回前景，準備重新開始掃描。")
        // Register MainActivity as an AreaChangeListener when it comes to foreground
        BleScannerManager.addAreaChangeListener(this)
        // Update UI immediately with current known area
        // 這會觸發 onAreaChanged -> updateGuideContent，進而停止任何之前播放的語音
        // 並且在 updateGuideContent 中會重置 UI 狀態，準備為新區域播放語音
        onAreaChanged(BleScannerManager.currentArea) // 這行會確保回到 MainActivity 時，UI 和語音狀態是基於最新區域的

        // Ensure scanning is active if permissions allow
        if (BleScannerManager.isBluetoothEnabled() && checkBluetoothScanPermission()) {
            BleScannerManager.startScan()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        // Optionally stop scan completely if app is truly exiting,
        // but typically you let the manager handle its own lifecycle based on app state.
        // BleScannerManager.stopScan() // Consider when to fully stop, usually on app termination
    }
}
