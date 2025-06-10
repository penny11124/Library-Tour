package com.techmania.librarytour

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MapActivity : AppCompatActivity(), AreaChangeListener {

    private lateinit var mapView: ImageView
    private lateinit var redDotView: View
    private lateinit var backButton: Button
    private lateinit var mapInfoTextView: TextView

    // 根据floor_1f.jpg精確調整這些座標
    // x 值是從左到右的比例 (0.0 - 1.0)
    // y 值是從上到下的比例 (0.0 - 1.0)
    private val zonePositions = mapOf(
        "D" to Pair(0.58f, 0.60f), // 知識集 新知,主題展 (中央圓形區域)
        "E" to Pair(0.85f, 0.70f),  // 輕學區
        "F" to Pair(0.70f, 0.80f) // 清沙龍
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapImage)
        redDotView = findViewById(R.id.redDot)
        backButton = findViewById(R.id.backButton)
        mapInfoTextView = findViewById(R.id.mapInfoTextView)

        backButton.setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Register MapActivity as an AreaChangeListener when it comes to foreground
        BleScannerManager.addAreaChangeListener(this)
        // Immediately update UI with the current known area
        onAreaChanged(BleScannerManager.currentArea)
    }

    override fun onPause() {
        super.onPause()
        // Unregister MapActivity as an AreaChangeListener when it goes to background
        BleScannerManager.removeAreaChangeListener(this)
    }

    // --- AreaChangeListener Implementation ---
    override fun onAreaChanged(newArea: String?) {
        runOnUiThread { // Ensure UI updates are on the main thread
            moveRedDotToZone(newArea)
        }
    }

    private fun moveRedDotToZone(zone: String?) {
        mapView.post {
            val pos = zonePositions[zone]
            if (pos == null) {
                redDotView.visibility = View.GONE
                mapInfoTextView.text = "無法估計目前位置，請確保您在導覽點附近。"
                return@post
            }

            redDotView.visibility = View.VISIBLE // 顯示紅點

            val imageWidth = mapView.width.toFloat()
            val imageHeight = mapView.height.toFloat()

            val x = pos.first * imageWidth
            val y = pos.second * imageHeight

            redDotView.x = x - redDotView.width / 2f
            redDotView.y = y - redDotView.height / 2f

            mapInfoTextView.text = "您目前在區域: $zone (估計位置)" // 更新說明文字
        }
    }
}