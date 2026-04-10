package tn.gov.education.examguard

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.graphics.*
import android.media.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.*

class TrackActivity : AppCompatActivity() {

    private var mac   = ""
    private var name  = ""
    private var proto = ""

    private var btAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isTracking = false
    private val handler = Handler(Looper.getMainLooper())

    // RSSI history for chart
    private val rssiHistory = ArrayDeque<Int>(maxOf = 80)
    private var currentRssi = -100

    // UI
    private lateinit var tvDevName  : TextView
    private lateinit var tvDevMac   : TextView
    private lateinit var tvRssi     : TextView
    private lateinit var tvDist     : TextView
    private lateinit var tvZone     : TextView
    private lateinit var chartView  : RssiChartView
    private lateinit var tvStatus   : TextView
    private lateinit var btnStop    : Button
    private lateinit var tvGuide    : TextView
    private lateinit var pbSignal   : ProgressBar

    // Sound
    private var toneGen: ToneGenerator? = null
    private var beepRunnable: Runnable? = null
    private var isMuted = false
    private var lastBeepZone = ""

    companion object {
        const val SCAN_PERIOD = 3000L
    }

    // ── Classic BT receiver ───────────────────────────────────────
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val dev = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                if (dev?.address == mac && rssi > -127) updateRssi(rssi)
            }
            if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                handler.postDelayed({ if (isTracking) startClassicScan() }, 500)
            }
        }
    }

    // ── BLE scan callback ─────────────────────────────────────────
    private val bleCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            if (result.device.address == mac) updateRssi(result.rssi)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.find { it.device.address == mac }?.let { updateRssi(it.rssi) }
        }
    }

    private fun updateRssi(rssi: Int) {
        currentRssi = rssi
        rssiHistory.addLast(rssi)
        if (rssiHistory.size > 80) rssiHistory.removeFirst()
        runOnUiThread { refreshUI(rssi) }
    }

    // ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mac   = intent.getStringExtra("mac")   ?: ""
        name  = intent.getStringExtra("name")  ?: mac
        proto = intent.getStringExtra("proto") ?: "BLE"

        setContentView(R.layout.activity_track)

        tvDevName = findViewById(R.id.tvTrackName)
        tvDevMac  = findViewById(R.id.tvTrackMac)
        tvRssi    = findViewById(R.id.tvTrackRssi)
        tvDist    = findViewById(R.id.tvTrackDist)
        tvZone    = findViewById(R.id.tvTrackZone)
        chartView = findViewById(R.id.chartView)
        tvStatus  = findViewById(R.id.tvTrackStatus)
        btnStop   = findViewById(R.id.btnTrackStop)
        tvGuide   = findViewById(R.id.tvGuide)
        pbSignal  = findViewById(R.id.pbTrackSignal)

        tvDevName.text = name.ifBlank { mac }
        tvDevMac.text  = mac

        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 80) } catch (_: Exception) {}

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter

        // Register receivers
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(classicReceiver, filter)

        btnStop.setOnClickListener { finish() }

        // Mute button
        val btnMute = findViewById<Button>(R.id.btnMute)
        btnMute.setOnClickListener {
            isMuted = !isMuted
            btnMute.text = if (isMuted) "🔇 كتم" else "🔊 صوت"
            if (isMuted) stopBeep()
        }

        startTracking()
    }

    private fun startTracking() {
        isTracking = true
        tvStatus.text = "تتبع الجهاز: ${name.ifBlank { mac }}"

        // BLE
        if (proto == "BLE" || proto == "BLE") startBleScan()
        // Always try classic too
        startClassicScan()
    }

    private fun startBleScan() {
        bleScanner = btAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        // Filter by MAC address
        val filters = listOf(
            ScanFilter.Builder().setDeviceAddress(mac).build()
        )
        try {
            bleScanner?.startScan(filters, settings, bleCb)
        } catch (_: Exception) {
            // Try without filter
            try { bleScanner?.startScan(null, settings, bleCb) } catch (_: Exception) {}
        }
        // Restart periodically
        handler.postDelayed({
            if (isTracking) {
                try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
                handler.postDelayed({ if (isTracking) startBleScan() }, 200)
            }
        }, SCAN_PERIOD)
    }

    private fun startClassicScan() {
        try {
            btAdapter?.cancelDiscovery()
            btAdapter?.startDiscovery()
        } catch (_: SecurityException) {}
    }

    private fun refreshUI(rssi: Int) {
        val distM = 10.0.pow((-59.0 - rssi) / (10.0 * 2.7))
        val distStr = when {
            distM < 1   -> "< 1 م"
            distM < 10  -> "%.1f م".format(distM)
            else        -> "%.0f م".format(distM)
        }
        val (zone, color, beepMs) = when {
            rssi >= -45 -> Triple("🚨 فتش التلميذ الآن!", Color.parseColor("#CC0000"), 150)
            rssi >= -55 -> Triple("🔴 جهاز قريب جداً!", Color.parseColor("#FF4400"), 300)
            rssi >= -65 -> Triple("🟠 يقترب...",         Color.parseColor("#FF8800"), 700)
            rssi >= -75 -> Triple("🟡 جهاز مرصود",        Color.parseColor("#FFBB00"), 1500)
            else        -> Triple("🟢 بعيد",              Color.parseColor("#33AA55"), 0)
        }
        val pct = ((rssi + 95.0) / 65.0 * 100).toInt().coerceIn(0, 100)

        tvRssi.text = "$rssi dBm"
        tvRssi.setTextColor(color)
        tvDist.text = distStr
        tvZone.text = zone
        tvZone.setTextColor(color)
        pbSignal.progress = pct
        pbSignal.progressTintList = android.content.res.ColorStateList.valueOf(color)
        chartView.setData(rssiHistory.toList())

        // Guide text
        tvGuide.text = when {
            rssi >= -55 -> "← ابحث عن الجهاز في المنطقة المحيطة →"
            rssi >= -65 -> "→ امش في هذا الاتجاه للاقتراب ←"
            else        -> "ابدأ بالتحرك للعثور على الجهاز"
        }

        // Sound
        if (beepMs > 0 && zone != lastBeepZone) {
            lastBeepZone = zone
            startBeep(beepMs)
        } else if (beepMs == 0) {
            stopBeep()
            lastBeepZone = ""
        }
    }

    // ── Beep logic ────────────────────────────────────────────────
    private fun startBeep(intervalMs: Int) {
        stopBeep()
        if (isMuted) return
        beepRunnable = object : Runnable {
            override fun run() {
                if (!isMuted) {
                    try {
                        toneGen?.startTone(
                            when {
                                intervalMs <= 200 -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                                intervalMs <= 400 -> ToneGenerator.TONE_PROP_BEEP2
                                else              -> ToneGenerator.TONE_PROP_BEEP
                            }, (intervalMs * 0.6).toInt()
                        )
                    } catch (_: Exception) {}
                    try { vibrate(intervalMs / 3) } catch (_: Exception) {}
                }
                handler.postDelayed(this, intervalMs.toLong())
            }
        }
        handler.post(beepRunnable!!)
    }

    private fun stopBeep() {
        beepRunnable?.let { handler.removeCallbacks(it) }
        beepRunnable = null
    }

    private fun vibrate(ms: Int) {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(ms.toLong())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        stopBeep()
        try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
        try { btAdapter?.cancelDiscovery() } catch (_: Exception) {}
        try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
        toneGen?.release()
        handler.removeCallbacksAndMessages(null)
    }
}

// ─── RSSI Chart Custom View ───────────────────────────────────────────────────
class RssiChartView(context: android.content.Context, attrs: android.util.AttributeSet? = null)
    : android.view.View(context, attrs) {

    private var data = listOf<Int>()

    private val bgPaint   = Paint().apply { color = Color.parseColor("#1A1A2E"); isAntiAlias = true }
    private val gridPaint = Paint().apply { color = Color.parseColor("#333355"); strokeWidth = 1f; isAntiAlias = true }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#4488FF"); strokeWidth = 3f
        isAntiAlias = true; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#334488FF".replace("33", "22")); isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.parseColor("#888899"); textSize = 24f; isAntiAlias = true
    }
    private val currentPaint = Paint().apply {
        color = Color.WHITE; strokeWidth = 2f; isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }

    fun setData(newData: List<Int>) {
        data = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 60f

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Grid lines at -20, -40, -60, -80, -100
        val levels = listOf(-20, -40, -60, -80, -100)
        levels.forEach { dBm ->
            val y = pad + (dBm.toFloat() + 20f) / (-100f + 20f) * (h - 2 * pad)
            canvas.drawLine(pad, y, w - pad, y, gridPaint)
            canvas.drawText("$dBm", 2f, y + 8f, textPaint)
        }

        if (data.size < 2) {
            canvas.drawText("جارٍ جمع البيانات...", w / 2 - 100, h / 2, textPaint)
            return
        }

        val stepX = (w - 2 * pad) / (data.size - 1)
        fun rssiToY(rssi: Int) = pad + (rssi.toFloat() + 20f) / (-100f + 20f) * (h - 2 * pad)

        // Fill area
        val fillPath = Path()
        fillPath.moveTo(pad, rssiToY(data.first()))
        data.forEachIndexed { i, rssi ->
            fillPath.lineTo(pad + i * stepX, rssiToY(rssi))
        }
        fillPath.lineTo(pad + (data.size - 1) * stepX, h - pad)
        fillPath.lineTo(pad, h - pad)
        fillPath.close()
        // Color fill based on last RSSI
        val lastRssi = data.last()
        fillPaint.color = when {
            lastRssi >= -45 -> Color.parseColor("#33CC0000")
            lastRssi >= -55 -> Color.parseColor("#33FF4400")
            lastRssi >= -65 -> Color.parseColor("#33FF8800")
            else            -> Color.parseColor("#332244AA")
        }
        canvas.drawPath(fillPath, fillPaint)

        // Line
        linePaint.color = when {
            lastRssi >= -45 -> Color.parseColor("#FF4444")
            lastRssi >= -55 -> Color.parseColor("#FF6600")
            lastRssi >= -65 -> Color.parseColor("#FFAA00")
            else            -> Color.parseColor("#4488FF")
        }
        val path = Path()
        path.moveTo(pad, rssiToY(data.first()))
        data.forEachIndexed { i, rssi -> path.lineTo(pad + i * stepX, rssiToY(rssi)) }
        canvas.drawPath(path, linePaint)

        // Current value dashed line
        val curY = rssiToY(lastRssi)
        canvas.drawLine(pad, curY, w - pad, curY, currentPaint)
    }
}
