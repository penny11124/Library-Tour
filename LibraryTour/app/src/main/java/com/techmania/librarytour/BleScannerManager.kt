package com.techmania.librarytour

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat

interface AreaChangeListener {
    fun onAreaChanged(newArea: String?)
}

object BleScannerManager {

    private const val TAG = "BleScannerManager"

    private var applicationContext: Context? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var bleScanner: BluetoothLeScanner
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val scanPeriod: Long = 8000 // Scan for 8 seconds
    private val scanInterval: Long = 4000 // Rest for 4 seconds
    private val STALE_BEACON_TIMEOUT = scanInterval + 2000L // Beacons considered stale after 6 seconds

    data class BeaconInfo(
        val rssiHistory: MutableList<Int> = mutableListOf(),
        var lastSeenTimestamp: Long = 0L
    )
    private val beaconDataMap = mutableMapOf<String, BeaconInfo>()
    private const val RSSI_HISTORY_SIZE = 5

    private val MIN_RSSI_THRESHOLD = -80

    private var _currentArea: String? = null // Private mutable backing property
    val currentArea: String?
        get() = _currentArea // Public read-only property

    // List of listeners to notify
    private val areaChangeListeners = mutableListOf<AreaChangeListener>()

    // --- Public API ---
    fun initialize(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter == null) {
                Log.e(TAG, "Device does not support Bluetooth")
            }
        }
    }

    fun startScan() {
        if (!isBluetoothEnabled() || isScanning) {
            Log.d(TAG, "Cannot start scan: Bluetooth not enabled or already scanning.")
            return
        }
        val context = applicationContext ?: run {
            Log.e(TAG, "Application context not initialized for BleScannerManager.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted.")
            return
        }

        bluetoothAdapter?.let { adapter ->
            bleScanner = adapter.bluetoothLeScanner
            Log.d(TAG, "===== Starting BLE scan =====")

            // Ensure previous scan is stopped before starting a new one
            stopScanInternal()

            handler.postDelayed({
                if (isScanning) {
                    Log.d(TAG, "===== Stopping BLE scan (period ended) =====")
                    stopScanInternal()
                    // Schedule next scan after interval
                    handler.postDelayed({ startScan() }, scanInterval)
                }
            }, scanPeriod)

            isScanning = true
            bleScanner.startScan(leScanCallback)
        } ?: Log.e(TAG, "BluetoothAdapter is null, cannot start scan.")
    }

    fun stopScan() {
        Log.d(TAG, "Stopping BLE scan externally.")
        stopScanInternal()
        handler.removeCallbacksAndMessages(null) // Remove all pending scan cycles
    }

    private fun stopScanInternal() {
        if (isScanning && ::bleScanner.isInitialized) {
            val context = applicationContext ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot stop scan: BLUETOOTH_SCAN permission not granted.")
                return
            }
            bleScanner.stopScan(leScanCallback)
            isScanning = false
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun addAreaChangeListener(listener: AreaChangeListener) {
        if (!areaChangeListeners.contains(listener)) {
            areaChangeListeners.add(listener)
            // Immediately notify new listener with current area
            listener.onAreaChanged(_currentArea)
        }
    }

    fun removeAreaChangeListener(listener: AreaChangeListener) {
        areaChangeListeners.remove(listener)
    }

    // --- Private Scan Callback ---
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val context = applicationContext ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission not granted for scan result.")
                return
            }

            val name = result.device.name ?: "Unnamed"
            val rssi = result.rssi

            if (name in listOf("ESP32_LibA", "ESP32_LibB", "ESP32_LibC"))  {
                if (rssi >= MIN_RSSI_THRESHOLD) {
                    val beaconInfo = beaconDataMap.getOrPut(name) { BeaconInfo() }
                    beaconInfo.lastSeenTimestamp = System.currentTimeMillis()
                    beaconInfo.rssiHistory.add(rssi)
                    if (beaconInfo.rssiHistory.size > RSSI_HISTORY_SIZE) {
                        beaconInfo.rssiHistory.removeAt(0)
                    }
                    // Log.d(TAG, "Accepted Beacon: $name, RSSI: $rssi, Avg RSSI: ${beaconInfo.rssiHistory.average().toInt()}")
                } else {
                    // 如果 RSSI 低於閾值，即使收到了這個 Beacon，也將其從 map 中移除
                    // 這樣它就不會影響區域判斷
                    beaconDataMap.remove(name)
                    // Log.d(TAG, "Rejected Beacon (RSSI too low): $name, RSSI: $rssi")
                }
            }
            evaluateCurrentArea()
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE scan failed, error code: $errorCode")
            isScanning = false
        }
    }

    private fun evaluateCurrentArea(){
        val now = System.currentTimeMillis()
        val freshAndStrongBeacons = beaconDataMap.filter { entry ->
            val beaconInfo = entry.value
            val averageRssi = if (beaconInfo.rssiHistory.isNotEmpty()) beaconInfo.rssiHistory.average() else Double.NEGATIVE_INFINITY
            (now - beaconInfo.lastSeenTimestamp < STALE_BEACON_TIMEOUT) && (averageRssi >= MIN_RSSI_THRESHOLD)
        }.toMutableMap() // 轉為 mutableMap 方便後續使用

        val strongestBeacon = if (freshAndStrongBeacons.isNotEmpty()) {
            freshAndStrongBeacons.mapValues { it.value.rssiHistory.average().toInt() }
                .maxByOrNull { it.value }?.key
        } else {
            null
        }

        val newlyDetectedArea = when (strongestBeacon) {
            "ESP32_LibA" -> "D"
            "ESP32_LibB" -> "F"
            "ESP32_LibC" -> "E"
            else -> null
        }

        if (newlyDetectedArea != _currentArea) {
            Log.d(TAG, "Area change detected: $_currentArea -> $newlyDetectedArea")
            _currentArea = newlyDetectedArea
            // Notify all registered listeners on the main thread
            handler.post {
                areaChangeListeners.forEach { listener ->
                    listener.onAreaChanged(_currentArea)
                }
            }
        }
    }
}