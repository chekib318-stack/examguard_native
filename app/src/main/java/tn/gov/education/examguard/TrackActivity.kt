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

    private var mac      = ""
    private var name     = ""
    private var proto    = ""
    private var isClassic = false  // true = Classic BR/EDR, false = BLE

    private var btAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var isTracking = false
    private val handler = Handler(Looper.getMainLooper())

    // RSSI data
    private val rssiHistory = ArrayList<Int>(120)
    private var kalmanRssi  = -100.0
    private var kalmanP     = 2.0
    private val kalmanR     = 4.0   // measurement noise — increase = smoother
    private val kalmanQ     = 0.08  // process noise — increase = faster response
    private var readCount   = 0
    private var lastUpdateMs = 0L

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
    private var toneGen      : ToneGenerator? = null
    private var beepRunnable : Runnable?      = null
    private var isMuted      = false
    private var lastZone     = ""

    companion object {
        const val RSSI_CRITICAL  = -45
        const val RSSI_DANGER    = -55
        const val RSSI_WARNING   = -65
        const val RSSI_WATCH     = -78
        const val GATT_READ_MS   = 300L
        const val BLE_RESTART_MS = 2000L
    }

    // ═══════════════════════════════════════════════════════════════
    // CLASSIC BR/EDR: ACTION_FOUND broadcast (only method available)
    // ═══════════════════════════════════════════════════════════════
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(
                        BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    if (dev?.address == mac && rssi > -127) {
                        runOnUiThread {
                            tvStatus.text = "Classic — وُجد الجهاز: $rssi dBm"
                        }
                        processNewRssi(rssi)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (isTracking && isClassic) {
                        // Restart immediately — no delay
                        handler.postDelayed({ if (isTracking) restartClassicDiscovery() }, 300)
                    }
                }
            }
        }
    }

    private fun restartClassicDiscovery() {
        try {
            btAdapter?.cancelDiscovery()
            val started = btAdapter?.startDiscovery() ?: false
            runOnUiThread {
                tvStatus.text = if (started) "Classic — جارٍ البحث..." else "تعذّر إطلاق البحث"
            }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // BLE: GATT readRemoteRssi (best — every 300ms)
    // ═══════════════════════════════════════════════════════════════
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread { tvStatus.text = "✅ GATT متصل — قراءة RSSI كل ${GATT_READ_MS}ms" }
                    handler.postDelayed({ if (isTracking) g.readRemoteRssi() }, 200)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runOnUiThread { tvStatus.text = "⚠️ GATT انقطع — إعادة الاتصال..." }
                    gatt?.close(); gatt = null
                    handler.postDelayed({ if (isTracking) connectGatt() }, 1500)
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && isTracking) {
                processNewRssi(rssi)
                handler.postDelayed({ if (isTracking) g.readRemoteRssi() }, GATT_READ_MS)
            } else if (isTracking) {
                // GATT read failed — retry
                handler.postDelayed({ if (isTracking) g.readRemoteRssi() }, GATT_READ_MS * 2)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BLE: Passive scan (backup when GATT fails to connect)
    // ═══════════════════════════════════════════════════════════════
    private val bleCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            if (result.device.address == mac) {
                processNewRssi(result.rssi)
            }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.find { it.device.address == mac }?.let { processNewRssi(it.rssi) }
        }
        override fun onScanFailed(code: Int) {
            runOnUiThread { tvStatus.text = "BLE scan فشل ($code)" }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Kalman-filtered RSSI processing
    // ═══════════════════════════════════════════════════════════════
    private fun processNewRssi(raw: Int) {
        // Kalman update
        val gain    = kalmanP / (kalmanP + kalmanR)
        kalmanRssi  = kalmanRssi + gain * (raw - kalmanRssi)
        kalmanP     = (1 - gain) * kalmanP + kalmanQ
        val smooth  = kalmanRssi.toInt()

        rssiHistory.add(smooth)
        if (rssiHistory.size > 120) rssiHistory.removeAt(0)

        readCount++
        lastUpdateMs = System.currentTimeMillis()

        runOnUiThread { refreshUI(smooth, raw) }
    }

    // ═══════════════════════════════════════════════════════════════
    // onCreate
    // ═══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mac      = intent.getStringExtra("mac")   ?: ""
        name     = intent.getStringExtra("name")  ?: mac
        proto    = intent.getStringExtra("proto") ?: "BLE"
        isClassic = proto.contains("Classic", ignoreCase = true) ||
                    proto.contains("BR/EDR", ignoreCase = true)

        // Init Kalman at -90 dBm (typical idle)
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
        tvDevMac.text  = "$mac  [${if (isClassic) "Classic BR/EDR" else "BLE"}]"

        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) {}

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter

        // Always register Classic receiver (needed for both types + discovery restart)
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

        // Show what strategy we'll use
        val strategyMsg = if (isClassic)
            "Classic BR/EDR — مسح متكرر كل ~12 ث"
        else
            "BLE — GATT connection كل ${GATT_READ_MS}ms"

        tvStatus.text = "بدء التتبع ($strategyMsg)..."

        startTracking()
    }

    private fun startTracking() {
        isTracking = true

        if (isClassic) {
            // ── Classic BR/EDR ────────────────────────────────────
            // ONLY method: BluetoothAdapter.startDiscovery()
            // Scans all nearby Classic devices every ~12s
            // ACTION_FOUND fires when our target is found
            restartClassicDiscovery()
            tvGuide.text = "Classic BT — تحرك ببطء نحو الجهاز\n(تحديث كل ~12 ثانية)"
        } else {
            // ── BLE device ────────────────────────────────────────
            // Strategy 1: GATT connection (best — continuous RSSI)
            connectGatt()
            // Strategy 2: BLE passive scan (runs in parallel as backup)
            startBleScan()
            tvGuide.text = "تحرك ببطء وراقب الإشارة"
        }
    }

    private fun connectGatt() {
        try {
            val device = btAdapter?.getRemoteDevice(mac) ?: return
            gatt?.close(); gatt = null
            gatt = if (Build.VERSION.SDK_INT >= 23)
                device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            else
                device.connectGatt(this, false, gattCallback)
            tvStatus.text = "GATT — جارٍ الاتصال..."
        } catch (e: Exception) {
            tvStatus.text = "GATT فشل: ${e.message} — BLE scan فقط"
        }
    }

    private fun startBleScan() {
        bleScanner = btAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        val filters = try {
            listOf(ScanFilter.Builder().setDeviceAddress(mac).build())
        } catch (_: Exception) { null }

        try {
            bleScanner?.startScan(filters, settings, bleCb)
        } catch (_: Exception) {
            try { bleScanner?.startScan(null, settings, bleCb) } catch (_: Exception) {}
        }

        // Restart BLE scan periodically
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isTracking || isClassic) return
                try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
                handler.postDelayed({
                    if (isTracking && !isClassic) {
                        try { bleScanner?.startScan(filters, settings, bleCb) }
                        catch (_: Exception) {}
                    }
                }, 150)
                handler.postDelayed(this, BLE_RESTART_MS)
            }
        }, BLE_RESTART_MS)
    }

    // ═══════════════════════════════════════════════════════════════
    // UI refresh
    // ═══════════════════════════════════════════════════════════════
    private fun refreshUI(rssi: Int, raw: Int) {
        val distM   = 10.0.pow((-59.0 - rssi) / (10.0 * 2.7))
        val distStr = when {
            distM < 0.5 -> "< 0.5 م"
            distM < 10  -> "%.1f م".format(distM)
            else        -> "%.0f م".format(distM)
        }

        val zone  : String
        val color : Int
        val label : String
        val beepMs: Int

        when {
            rssi >= RSSI_CRITICAL -> { zone="critical"; color=Color.parseColor("#AA0000")
                label="🚨 فتش التلميذ الآن!"; beepMs=120 }
            rssi >= RSSI_DANGER   -> { zone="danger";   color=Color.parseColor("#DD2200")
                label="🔴 الجهاز قريب جداً!"; beepMs=280 }
            rssi >= RSSI_WARNING  -> { zone="warning";  color=Color.parseColor("#FF7700")
                label="🟠 يقترب...";           beepMs=700 }
            rssi >= RSSI_WATCH    -> { zone="watch";    color=Color.parseColor("#FFBB00")
                label="🟡 جهاز مرصود";         beepMs=2000 }
            else                  -> { zone="clear";    color=Color.parseColor("#22AA55")
                label="🟢 بعيد";               beepMs=0 }
        }

        val pct = ((rssi + 95.0) / 65.0 * 100).toInt().coerceIn(0, 100)

        tvRssi.text = "$rssi dBm"
        tvRssi.setTextColor(color)
        tvDist.text = distStr
        tvZone.text = label
        tvZone.setTextColor(color)
        pbSignal.progress = pct
        pbSignal.progressTintList = android.content.res.ColorStateList.valueOf(color)

        tvGuide.text = when (zone) {
            "critical" -> "⬅ الجهاز أمامك مباشرة ➡"
            "danger"   -> "⬅ ابحث في نطاق 1 متر ➡"
            "warning"  -> "➡ امشِ ببطء نحو الإشارة ⬅"
            else       -> if (isClassic) "تحرك ببطء — تحديث كل ~12 ث"
                          else "تحرك ببطء وراقب الإشارة"
        }

        val elapsed = if (lastUpdateMs > 0)
            "منذ ${(System.currentTimeMillis() - lastUpdateMs) / 1000}ث"
        else ""
        tvUpdated.text = "#$readCount | raw:$raw → smooth:$rssi | $elapsed"

        chartView.setData(rssiHistory.toList(), rssi)

        if (beepMs > 0 && zone != lastZone) {
            lastZone = zone; startBeep(beepMs)
        } else if (beepMs == 0) {
            stopBeep(); lastZone = ""
        }
    }

    // Sound
    private fun startBeep(ms: Int) {
        stopBeep()
        if (isMuted) return
        beepRunnable = object : Runnable {
            override fun run() {
                if (isMuted) return
                try {
                    toneGen?.startTone(when {
                        ms <= 150 -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                        ms <= 300 -> ToneGenerator.TONE_PROP_BEEP2
                        else      -> ToneGenerator.TONE_PROP_BEEP
                    }, (ms * 0.55).toInt())
                } catch (_: Exception) {}
                vibrate(ms / 4)
                handler.postDelayed(this, ms.toLong())
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

    private val bgPaint   = Paint().apply { color = Color.parseColor("#0A0A1A") }
    private val gridPaint = Paint().apply { color = Color.parseColor("#1A1A33"); strokeWidth = 0.8f }
    private val textPaint = Paint().apply { color = Color.parseColor("#444466"); textSize = 26f; isAntiAlias = true }
    private val dashPaint = Paint().apply {
        color = Colors.WHITE; strokeWidth = 1.5f; isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val linePaint = Paint().apply {
        strokeWidth = 3.5f; isAntiAlias = true; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }

    object Colors { val WHITE = Color.argb(120, 255, 255, 255) }

    fun setData(d: List<Int>, rssi: Int) { data = d; currentRssi = rssi; invalidate() }

    private fun yOf(rssi: Int, pad: Float, h: Float) =
        pad + (rssi.toFloat() + 20f) / (-100f + 20f) * (h - 2 * pad)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = 54f
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        listOf(-20, -40, -60, -80, -100).forEach { dBm ->
            val y = yOf(dBm, pad, h)
            canvas.drawLine(pad, y, w - 6f, y, gridPaint)
            canvas.drawText("$dBm", 2f, y + 9f, textPaint)
        }

        if (data.size < 2) {
            val noDataPaint = Paint().apply {
                color = Color.parseColor("#335588"); textSize = 34f; isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("في انتظار الإشارة...", w / 2, h / 2, noDataPaint)
            return
        }

        val step = (w - pad - 6f) / (data.size - 1)

        // Fill
        val fp = Path()
        fp.moveTo(pad, yOf(data.first(), pad, h))
        data.forEachIndexed { i, r -> fp.lineTo(pad + i * step, yOf(r, pad, h)) }
        fp.lineTo(pad + (data.size - 1) * step, h - pad)
        fp.lineTo(pad, h - pad); fp.close()
        fillPaint.color = when {
            currentRssi >= ZONE_CRITICAL -> Color.parseColor("#33880000")
            currentRssi >= ZONE_DANGER   -> Color.parseColor("#33CC2200")
            currentRssi >= ZONE_WARNING  -> Color.parseColor("#33AA5500")
            else                         -> Color.parseColor("#221133AA")
        }
        canvas.drawPath(fp, fillPaint)

        // Line
        linePaint.color = when {
            currentRssi >= ZONE_CRITICAL -> Color.parseColor("#FF3333")
            currentRssi >= ZONE_DANGER   -> Color.parseColor("#FF6600")
            currentRssi >= ZONE_WARNING  -> Color.parseColor("#FFAA00")
            else                         -> Color.parseColor("#4499FF")
        }
        val lp = Path()
        lp.moveTo(pad, yOf(data.first(), pad, h))
        data.forEachIndexed { i, r -> lp.lineTo(pad + i * step, yOf(r, pad, h)) }
        canvas.drawPath(lp, linePaint)

        // Current value line
        val cy = yOf(currentRssi, pad, h)
        canvas.drawLine(pad, cy, w - 6f, cy, dashPaint)

        // Current value label
        val valPaint = Paint().apply {
            color = Color.WHITE; textSize = 28f; isAntiAlias = true; textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("$currentRssi dBm", w - 8f, cy - 6f, valPaint)
    }

    companion object {
        private const val ZONE_CRITICAL = -45
        private const val ZONE_DANGER   = -55
        private const val ZONE_WARNING  = -65
    }
}
