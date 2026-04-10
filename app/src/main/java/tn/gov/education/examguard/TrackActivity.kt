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
    private var gatt: BluetoothGatt? = null
    private var isTracking = false
    private val handler = Handler(Looper.getMainLooper())

    // RSSI data
    private val rssiHistory = ArrayList<Int>(100)
    private var currentRssi = -100
    private var kalmanRssi   = -100.0   // smoothed RSSI

    // UI
    private lateinit var tvDevName : TextView
    private lateinit var tvDevMac  : TextView
    private lateinit var tvRssi    : TextView
    private lateinit var tvDist    : TextView
    private lateinit var tvZone    : TextView
    private lateinit var chartView : RssiChartView
    private lateinit var tvStatus  : TextView
    private lateinit var btnStop   : Button
    private lateinit var tvGuide   : TextView
    private lateinit var pbSignal  : ProgressBar
    private lateinit var tvUpdated : TextView

    // Sound
    private var toneGen   : ToneGenerator? = null
    private var beepRunnable: Runnable?    = null
    private var isMuted   = false
    private var lastZone  = ""
    private var rssiReadCount = 0

    // Kalman filter state
    private var kalmanP = 1.0    // estimation error covariance
    private val kalmanR = 3.0    // measurement noise
    private val kalmanQ = 0.1    // process noise

    companion object {
        // RSSI thresholds — calibrated for 1m apart phones
        const val ZONE_CRITICAL = -45  // < 0.6m  FOUTEZ NOW
        const val ZONE_DANGER   = -55  // ~ 1m
        const val ZONE_WARNING  = -65  // ~ 2m
        const val ZONE_WATCH    = -78  // ~ 5m

        const val GATT_READ_INTERVAL_MS = 300L  // read RSSI every 300ms via GATT
        const val SCAN_RESTART_MS       = 2000L  // restart BLE scan every 2s
    }

    // ── GATT callback — most accurate RSSI method ────────────────
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { tvStatus.text = "متصل عبر GATT — قراءة RSSI..." }
                // Start continuous RSSI reading
                scheduleGattRssiRead()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { tvStatus.text = "انقطع الاتصال — إعادة الاتصال..." }
                handler.postDelayed({ if (isTracking) reconnectGatt() }, 1000)
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && isTracking) {
                rssiReadCount++
                processNewRssi(rssi)
                // Schedule next read
                handler.postDelayed({ if (isTracking) g.readRemoteRssi() },
                    GATT_READ_INTERVAL_MS)
            }
        }
    }

    private fun scheduleGattRssiRead() {
        handler.postDelayed({
            if (isTracking) gatt?.readRemoteRssi()
        }, 200)
    }

    // ── BLE scan callback (fallback if GATT fails) ───────────────
    private val bleCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            if (result.device.address == mac) {
                processNewRssi(result.rssi)
            }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.find { it.device.address == mac }?.let { processNewRssi(it.rssi) }
        }
    }

    // ── Classic BT receiver ──────────────────────────────────────
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val dev = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                if (dev?.address == mac && rssi > -127) processNewRssi(rssi)
            }
            if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                // Restart classic scan immediately
                handler.postDelayed({ if (isTracking) startClassicScan() }, 200)
            }
        }
    }

    // ── Process new RSSI with Kalman filter ──────────────────────
    private fun processNewRssi(raw: Int) {
        // Kalman filter — smooths noisy RSSI readings
        val innovation = raw - kalmanRssi
        val kalmanGain = kalmanP / (kalmanP + kalmanR)
        kalmanRssi = kalmanRssi + kalmanGain * innovation
        kalmanP    = (1 - kalmanGain) * kalmanP + kalmanQ

        val smoothed = kalmanRssi.toInt()
        currentRssi  = smoothed

        rssiHistory.add(smoothed)
        if (rssiHistory.size > 100) rssiHistory.removeAt(0)

        runOnUiThread { refreshUI(smoothed, raw) }
    }

    // ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mac   = intent.getStringExtra("mac")   ?: ""
        name  = intent.getStringExtra("name")  ?: mac
        proto = intent.getStringExtra("proto") ?: "BLE"

        // Init Kalman with default
        kalmanRssi = -90.0

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
        tvUpdated = findViewById(R.id.tvUpdated)

        tvDevName.text = name.ifBlank { mac }
        tvDevMac.text  = mac

        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) {}

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(classicReceiver, filter)

        btnStop.setOnClickListener { finish() }

        findViewById<Button>(R.id.btnMute).setOnClickListener {
            isMuted = !isMuted
            (it as Button).text = if (isMuted) "🔇 كتم" else "🔊 صوت"
            if (isMuted) stopBeep()
        }

        startTracking()
    }

    private fun startTracking() {
        isTracking = true
        tvStatus.text = "بدء التتبع..."

        // Strategy 1: GATT connection (best for BLE — gives continuous RSSI)
        connectGatt()

        // Strategy 2: BLE passive scan (catches devices not accepting GATT)
        startBleScan()

        // Strategy 3: Classic BT discovery (for non-BLE devices)
        startClassicScan()
    }

    private fun connectGatt() {
        try {
            val device = btAdapter?.getRemoteDevice(mac) ?: return
            gatt?.close()
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(this, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(this, false, gattCallback)
            }
            tvStatus.text = "جارٍ الاتصال بـ GATT..."
        } catch (_: Exception) {
            tvStatus.text = "GATT غير متاح — مسح BLE..."
        }
    }

    private fun reconnectGatt() {
        try {
            gatt?.close()
            gatt = null
        } catch (_: Exception) {}
        connectGatt()
    }

    private fun startBleScan() {
        bleScanner = btAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        // Scan for THIS specific device
        val filters = try {
            listOf(ScanFilter.Builder().setDeviceAddress(mac).build())
        } catch (_: Exception) { null }

        try {
            bleScanner?.startScan(filters, settings, bleCb)
        } catch (_: Exception) {
            try { bleScanner?.startScan(null, settings, bleCb) } catch (_: Exception) {}
        }

        // Restart BLE scan every 2s to avoid Android throttling
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isTracking) return
                try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
                handler.postDelayed({
                    if (isTracking) {
                        try { bleScanner?.startScan(filters, settings, bleCb)
                        } catch (_: Exception) {}
                    }
                }, 100)
                handler.postDelayed(this, SCAN_RESTART_MS)
            }
        }, SCAN_RESTART_MS)
    }

    private fun startClassicScan() {
        try {
            btAdapter?.cancelDiscovery()
            btAdapter?.startDiscovery()
        } catch (_: Exception) {}
    }

    // ── Refresh UI ───────────────────────────────────────────────
    private fun refreshUI(rssi: Int, rawRssi: Int) {
        val distM = 10.0.pow((-59.0 - rssi) / (10.0 * 2.7))
        val distStr = when {
            distM < 0.5 -> "< 0.5 م"
            distM < 1   -> "%.1f م".format(distM)
            distM < 10  -> "%.1f م".format(distM)
            else        -> "%.0f م".format(distM)
        }

        val (zone, color, zoneLabel, beepMs) = when {
            rssi >= ZONE_CRITICAL -> Quad("critical", Color.parseColor("#AA0000"),
                "🚨 فتش التلميذ الآن!", 120)
            rssi >= ZONE_DANGER   -> Quad("danger",   Color.parseColor("#DD2200"),
                "🔴 الجهاز قريب جداً!", 280)
            rssi >= ZONE_WARNING  -> Quad("warning",  Color.parseColor("#FF7700"),
                "🟠 يقترب...",          700)
            rssi >= ZONE_WATCH    -> Quad("watch",    Color.parseColor("#FFBB00"),
                "🟡 جهاز مرصود",        2000)
            else                  -> Quad("clear",    Color.parseColor("#22AA55"),
                "🟢 بعيد",              0)
        }

        val pct = ((rssi + 95.0) / 65.0 * 100).toInt().coerceIn(0, 100)

        tvRssi.text  = "$rssi dBm"
        tvRssi.setTextColor(color)
        tvDist.text  = distStr
        tvZone.text  = zoneLabel
        tvZone.setTextColor(color)
        pbSignal.progress = pct
        pbSignal.progressTintList = android.content.res.ColorStateList.valueOf(color)
        tvUpdated.text = "#$rssiReadCount  raw:$rawRssi→smooth:$rssi"

        // Guide
        tvGuide.text = when (zone) {
            "critical" -> "← اتجه نحو التلميذ الأمامي →"
            "danger"   -> "← الجهاز أمامك مباشرة →"
            "warning"  -> "→ امشِ ببطء نحو الإشارة ←"
            else       -> "تحرك ببطء وراقب قوة الإشارة"
        }

        chartView.setData(rssiHistory.toList(), rssi)

        // Sound + vibration
        if (beepMs > 0 && zone != lastZone) {
            lastZone = zone
            startBeep(beepMs)
        } else if (beepMs == 0) {
            stopBeep(); lastZone = ""
        }
    }

    data class Quad<A,B,C,D>(val a:A, val b:B, val c:C, val d:D)
    private operator fun <A,B,C,D> Quad<A,B,C,D>.component1() = a
    private operator fun <A,B,C,D> Quad<A,B,C,D>.component2() = b
    private operator fun <A,B,C,D> Quad<A,B,C,D>.component3() = c
    private operator fun <A,B,C,D> Quad<A,B,C,D>.component4() = d

    // ── Sound ────────────────────────────────────────────────────
    private fun startBeep(intervalMs: Int) {
        stopBeep()
        if (isMuted) return
        beepRunnable = object : Runnable {
            override fun run() {
                if (isMuted) return
                try {
                    toneGen?.startTone(
                        when {
                            intervalMs <= 150 -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                            intervalMs <= 300 -> ToneGenerator.TONE_PROP_BEEP2
                            else              -> ToneGenerator.TONE_PROP_BEEP
                        }, (intervalMs * 0.55).toInt()
                    )
                } catch (_: Exception) {}
                vibrate(intervalMs / 4)
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
        if (ms < 20) return
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(),
                    VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms.toLong())
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        stopBeep()
        try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
        try { btAdapter?.cancelDiscovery() } catch (_: Exception) {}
        try { gatt?.disconnect(); gatt?.close(); gatt = null } catch (_: Exception) {}
        try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
        toneGen?.release()
        handler.removeCallbacksAndMessages(null)
    }
}

// ─── RSSI Chart ───────────────────────────────────────────────────────────────
class RssiChartView(ctx: android.content.Context, attrs: android.util.AttributeSet? = null)
    : android.view.View(ctx, attrs) {

    private var data        = listOf<Int>()
    private var currentRssi = -100

    private val bgPaint     = Paint().apply { color = Color.parseColor("#111122") }
    private val gridPaint   = Paint().apply {
        color = Color.parseColor("#222244"); strokeWidth = 0.8f }
    private val textPaint   = Paint().apply {
        color = Color.parseColor("#555577"); textSize = 26f; isAntiAlias = true }
    private val currentPaint= Paint().apply {
        color = Color.WHITE; strokeWidth = 1.5f; isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val linePaint   = Paint().apply {
        strokeWidth = 3f; isAntiAlias = true; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint   = Paint().apply {
        isAntiAlias = true; style = Paint.Style.FILL }

    fun setData(d: List<Int>, rssi: Int) {
        data = d; currentRssi = rssi; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = 52f
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Grid lines
        listOf(-20, -40, -60, -80, -100).forEach { dBm ->
            val y = rssiToY(dBm, pad, h)
            canvas.drawLine(pad, y, w - 8f, y, gridPaint)
            canvas.drawText("$dBm", 2f, y + 9f, textPaint)
        }

        if (data.size < 2) return

        val step = (w - pad - 8f) / (data.size - 1)

        // Fill area
        val fp = Path()
        fp.moveTo(pad, rssiToY(data.first(), pad, h))
        data.forEachIndexed { i, r -> fp.lineTo(pad + i * step, rssiToY(r, pad, h)) }
        fp.lineTo(pad + (data.size - 1) * step, h - pad)
        fp.lineTo(pad, h - pad)
        fp.close()
        fillPaint.color = when {
            currentRssi >= -45 -> Color.parseColor("#33CC0000")
            currentRssi >= -55 -> Color.parseColor("#33FF4400")
            currentRssi >= -65 -> Color.parseColor("#33FF8800")
            else               -> Color.parseColor("#222255AA")
        }
        canvas.drawPath(fp, fillPaint)

        // Line
        linePaint.color = when {
            currentRssi >= -45 -> Color.parseColor("#FF4444")
            currentRssi >= -55 -> Color.parseColor("#FF6600")
            currentRssi >= -65 -> Color.parseColor("#FFAA00")
            else               -> Color.parseColor("#4488FF")
        }
        val lp = Path()
        lp.moveTo(pad, rssiToY(data.first(), pad, h))
        data.forEachIndexed { i, r -> lp.lineTo(pad + i * step, rssiToY(r, pad, h)) }
        canvas.drawPath(lp, linePaint)

        // Current line
        val cy = rssiToY(currentRssi, pad, h)
        canvas.drawLine(pad, cy, w - 8f, cy, currentPaint)
    }

    private fun rssiToY(rssi: Int, pad: Float, h: Float) =
        pad + (rssi.toFloat() + 20f) / (-100f + 20f) * (h - 2 * pad)
}
