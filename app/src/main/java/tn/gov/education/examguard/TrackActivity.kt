package tn.gov.education.examguard

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.graphics.*
import android.location.LocationManager
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

    private var btAdapter : BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gatt      : BluetoothGatt? = null
    private var isTracking = false
    private val handler = Handler(Looper.getMainLooper())

    // RSSI state
    private val rssiHistory  = ArrayList<Int>(120)
    private var kalmanRssi   = -90.0
    private var kalmanP      = 2.0
    private val kalmanR      = 3.0   // lower = reacts faster
    private val kalmanQ      = 0.15
    private var readCount    = 0
    private var lastRawRssi  = -100
    private var gattOk       = false
    private var discoveryOk  = false

    // UI
    private lateinit var tvDevName: TextView
    private lateinit var tvDevMac : TextView
    private lateinit var tvRssi   : TextView
    private lateinit var tvDist   : TextView
    private lateinit var tvZone   : TextView
    private lateinit var tvGuide  : TextView
    private lateinit var tvStatus : TextView
    private lateinit var tvUpdated: TextView
    private lateinit var pbSignal : ProgressBar
    private lateinit var chartView: RssiChartView

    // Sound
    private var toneGen   : ToneGenerator? = null
    private var beepJob   : Runnable?      = null
    private var isMuted   = false
    private var lastZone  = ""

    companion object {
        const val RSSI_CRITICAL = -45
        const val RSSI_DANGER   = -55
        const val RSSI_WARNING  = -65
        const val RSSI_WATCH    = -78
        const val GATT_READ_MS  = 300L
    }

    // ──────────────────────────────────────────────────────────────
    // GATT — works for BLE AND many Classic devices (PCs, phones)
    // ──────────────────────────────────────────────────────────────
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gattOk = true
                    runOnUiThread { tvStatus.text = "✅ GATT متصل — قراءة كل ${GATT_READ_MS}ms" }
                    handler.postDelayed({ if (isTracking) g.readRemoteRssi() }, 300)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gattOk = false
                    runOnUiThread { tvStatus.text = "GATT انقطع — إعادة الاتصال..." }
                    gatt?.close(); gatt = null
                    handler.postDelayed({ if (isTracking) connectGatt() }, 2000)
                }
            }
        }
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && isTracking) {
                processRssi(rssi)
                handler.postDelayed({ if (isTracking && gattOk) g.readRemoteRssi() }, GATT_READ_MS)
            } else {
                handler.postDelayed({ if (isTracking && gattOk) g.readRemoteRssi() }, GATT_READ_MS * 3)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // BLE passive scan — fast, catches BLE + some Classic devices
    // ──────────────────────────────────────────────────────────────
    private val bleCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            if (r.device.address == mac) processRssi(r.rssi)
        }
        override fun onBatchScanResults(list: MutableList<ScanResult>) {
            list.find { it.device.address == mac }?.let { processRssi(it.rssi) }
        }
        override fun onScanFailed(code: Int) {
            runOnUiThread { tvStatus.text = "BLE scan فشل ($code)" }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Classic BT discovery — fallback, slow but universal
    // ──────────────────────────────────────────────────────────────
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (-100).toShort()).toInt()
                    if (dev?.address == mac && rssi > -127) {
                        discoveryOk = true
                        processRssi(rssi)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (isTracking && !gattOk) {
                        // Only restart discovery if GATT not working
                        handler.postDelayed({ if (isTracking && !gattOk) startDiscovery() }, 500)
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Kalman filter
    // ──────────────────────────────────────────────────────────────
    private fun processRssi(raw: Int) {
        lastRawRssi = raw
        val gain   = kalmanP / (kalmanP + kalmanR)
        kalmanRssi = kalmanRssi + gain * (raw - kalmanRssi)
        kalmanP    = (1 - gain) * kalmanP + kalmanQ
        val smooth = kalmanRssi.toInt()
        rssiHistory.add(smooth)
        if (rssiHistory.size > 120) rssiHistory.removeAt(0)
        readCount++
        runOnUiThread { refreshUI(smooth, raw) }
    }

    // ──────────────────────────────────────────────────────────────
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
        tvGuide   = findViewById(R.id.tvGuide)
        tvStatus  = findViewById(R.id.tvTrackStatus)
        tvUpdated = findViewById(R.id.tvUpdated)
        pbSignal  = findViewById(R.id.pbTrackSignal)
        chartView = findViewById(R.id.chartView)

        tvDevName.text = name.ifBlank { mac.takeLast(11) }
        tvDevMac.text  = mac

        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) {}

        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btMgr.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(classicReceiver, filter)

        findViewById<Button>(R.id.btnTrackStop).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnMute).setOnClickListener {
            isMuted = !isMuted
            (it as Button).text = if (isMuted) "🔇 كتم" else "🔊 صوت"
            if (isMuted) stopBeep()
        }

        startTracking()
    }

    private fun startTracking() {
        isTracking = true

        // Check if location is enabled (required for Classic discovery on Android 9+)
        val locMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locEnabled = locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                         locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        tvStatus.text = "بدء المسح... (GPS: ${if (locEnabled) "✓" else "⚠️ مطلوب"})"

        // Strategy 1: GATT (fastest — 300ms interval, works for BLE + many Classic)
        connectGatt()

        // Strategy 2: BLE passive scan (parallel, catches BLE devices)
        startBleScan()

        // Strategy 3: Classic discovery (fallback, requires GPS on)
        if (locEnabled) startDiscovery()
        else showGpsWarning()

        // Watchdog: if no data after 8s, show help message
        handler.postDelayed({
            if (isTracking && readCount == 0) {
                runOnUiThread {
                    tvStatus.text = "⚠️ لا إشارة — تأكد من: GPS مفعّل، تصاريح ممنوحة"
                    tvGuide.text  = "اذهب إلى إعدادات الهاتف وفعّل الموقع (GPS)"
                }
            }
        }, 8000)
    }

    private fun connectGatt() {
        try {
            val device = btAdapter?.getRemoteDevice(mac) ?: return
            gatt?.close(); gatt = null
            gatt = if (Build.VERSION.SDK_INT >= 23)
                device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_AUTO)
            else
                device.connectGatt(this, false, gattCb)
            tvStatus.text = "GATT — جارٍ الاتصال..."
        } catch (e: Exception) {
            tvStatus.text = "GATT: ${e.message?.take(40)}"
        }
    }

    private fun startBleScan() {
        bleScanner = btAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0).build()
        val filters = try {
            listOf(ScanFilter.Builder().setDeviceAddress(mac).build())
        } catch (_: Exception) { null }
        try { bleScanner?.startScan(filters, settings, bleCb) }
        catch (_: Exception) {
            try { bleScanner?.startScan(null, settings, bleCb) } catch (_: Exception) {}
        }
        // Restart BLE scan every 2s
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isTracking) return
                try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
                handler.postDelayed({
                    if (isTracking) {
                        try { bleScanner?.startScan(filters, settings, bleCb) }
                        catch (_: Exception) {}
                    }
                }, 100)
                handler.postDelayed(this, 2000)
            }
        }, 2000)
    }

    private fun startDiscovery() {
        try {
            btAdapter?.cancelDiscovery()
            val ok = btAdapter?.startDiscovery() ?: false
            runOnUiThread {
                tvStatus.text = if (ok) "Classic — البحث جارٍ..." else "⚠️ فشل Discovery — فعّل GPS"
            }
        } catch (_: Exception) {}
    }

    private fun showGpsWarning() {
        tvStatus.text = "⚠️ GPS مطلوب للأجهزة Classic"
        tvGuide.text  = "فعّل الموقع (GPS) في إعدادات الهاتف\nثم أعِد المحاولة"
        // Show dialog
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle("الموقع (GPS) مطلوب")
                .setMessage("Android يطلب تفعيل الموقع لاكتشاف أجهزة Bluetooth Classic.\n\nيرجى تفعيل GPS ثم العودة.")
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // UI
    // ──────────────────────────────────────────────────────────────
    private fun refreshUI(rssi: Int, raw: Int) {
        val distM = 10.0.pow((-59.0 - rssi) / (10.0 * 2.7))
        val distStr = when {
            distM < 0.5 -> "< 0.5 م"
            distM < 10  -> "%.1f م".format(distM)
            else        -> "%.0f م".format(distM)
        }

        val (zone, color, label, beepMs) = when {
            rssi >= RSSI_CRITICAL -> Q("critical", "#AA0000", "🚨 فتش التلميذ الآن!", 120)
            rssi >= RSSI_DANGER   -> Q("danger",   "#DD2200", "🔴 الجهاز قريب جداً!",  280)
            rssi >= RSSI_WARNING  -> Q("warning",  "#FF7700", "🟠 يقترب...",            700)
            rssi >= RSSI_WATCH    -> Q("watch",    "#FFBB00", "🟡 جهاز مرصود",          2000)
            else                  -> Q("clear",    "#22AA55", "🟢 بعيد",                0)
        }

        val pct = ((rssi + 95.0) / 65.0 * 100).toInt().coerceIn(0, 100)
        tvRssi.text = "$rssi dBm"
        tvRssi.setTextColor(Color.parseColor(color))
        tvDist.text = distStr
        tvZone.text = label
        tvZone.setTextColor(Color.parseColor(color))
        pbSignal.progress = pct
        pbSignal.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))

        tvGuide.text = when (zone) {
            "critical" -> "⬅ أمامك مباشرة ➡"
            "danger"   -> "⬅ نطاق 1 متر — تفتيش ➡"
            "warning"  -> "➡ تحرك ببطء نحو الإشارة ⬅"
            else       -> "تحرك ببطء وراقب الإشارة"
        }

        val src = if (gattOk) "GATT" else if (discoveryOk) "Classic" else "BLE"
        tvUpdated.text = "#$readCount | $src | raw:$raw → smooth:$rssi"
        tvStatus.text  = "$src ✓ — ${readCount} تحديث"

        chartView.setData(rssiHistory.toList(), rssi)

        if (beepMs > 0 && zone != lastZone) { lastZone = zone; startBeep(beepMs) }
        else if (beepMs == 0) { stopBeep(); lastZone = "" }
    }

    data class Q(val z: String, val c: String, val l: String, val b: Int)
    private operator fun Q.component1() = z
    private operator fun Q.component2() = c
    private operator fun Q.component3() = l
    private operator fun Q.component4() = b

    private fun startBeep(ms: Int) {
        stopBeep(); if (isMuted) return
        beepJob = object : Runnable {
            override fun run() {
                if (isMuted) return
                try { toneGen?.startTone(when {
                    ms <= 150 -> ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                    ms <= 300 -> ToneGenerator.TONE_PROP_BEEP2
                    else      -> ToneGenerator.TONE_PROP_BEEP }, (ms * 0.55).toInt())
                } catch (_: Exception) {}
                try {
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= 26)
                        v.vibrate(VibrationEffect.createOneShot((ms/4).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                    else @Suppress("DEPRECATION") v.vibrate((ms/4).toLong())
                } catch (_: Exception) {}
                handler.postDelayed(this, ms.toLong())
            }
        }
        handler.post(beepJob!!)
    }

    private fun stopBeep() { beepJob?.let { handler.removeCallbacks(it) }; beepJob = null }

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

// ─── Chart ────────────────────────────────────────────────────────────────────
class RssiChartView(ctx: android.content.Context, attrs: android.util.AttributeSet? = null)
    : android.view.View(ctx, attrs) {

    private var data = listOf<Int>()
    private var cur  = -100

    private val bg   = Paint().apply { color = Color.parseColor("#0A0A1A") }
    private val grid = Paint().apply { color = Color.parseColor("#1A1A33"); strokeWidth = 0.8f }
    private val txt  = Paint().apply { color = Color.parseColor("#444466"); textSize = 26f; isAntiAlias = true }
    private val dash = Paint().apply {
        color = Color.argb(100, 255, 255, 255); strokeWidth = 1.5f; isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val line = Paint().apply { strokeWidth = 3.5f; isAntiAlias = true; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val val2 = Paint().apply { color = Color.WHITE; textSize = 28f; isAntiAlias = true
        textAlign = Paint.Align.RIGHT }

    fun setData(d: List<Int>, rssi: Int) { data = d; cur = rssi; invalidate() }

    private fun y(rssi: Int, pad: Float, h: Float) =
        pad + (rssi + 20f) / (-80f) * (h - 2 * pad)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = 52f
        canvas.drawRect(0f, 0f, w, h, bg)
        listOf(-20, -40, -60, -80, -100).forEach { dBm ->
            val yy = y(dBm, pad, h)
            canvas.drawLine(pad, yy, w - 6f, yy, grid)
            canvas.drawText("$dBm", 2f, yy + 9f, txt)
        }
        if (data.size < 2) {
            val nodata = Paint().apply { color = Color.parseColor("#335577"); textSize = 34f
                isAntiAlias = true; textAlign = Paint.Align.CENTER }
            canvas.drawText("في انتظار الإشارة...", w / 2, h / 2, nodata)
            return
        }
        val step = (w - pad - 6f) / (data.size - 1)

        // Fill
        val fp = Path()
        fp.moveTo(pad, y(data.first(), pad, h))
        data.forEachIndexed { i, r -> fp.lineTo(pad + i * step, y(r, pad, h)) }
        fp.lineTo(pad + (data.size - 1) * step, h - pad); fp.lineTo(pad, h - pad); fp.close()
        fill.color = when {
            cur >= -45 -> Color.parseColor("#33880000")
            cur >= -55 -> Color.parseColor("#33CC3300")
            cur >= -65 -> Color.parseColor("#33AA6600")
            else       -> Color.parseColor("#221133AA") }
        canvas.drawPath(fp, fill)

        // Line
        line.color = when {
            cur >= -45 -> Color.parseColor("#FF3333")
            cur >= -55 -> Color.parseColor("#FF6600")
            cur >= -65 -> Color.parseColor("#FFAA00")
            else       -> Color.parseColor("#4499FF") }
        val lp = Path()
        lp.moveTo(pad, y(data.first(), pad, h))
        data.forEachIndexed { i, r -> lp.lineTo(pad + i * step, y(r, pad, h)) }
        canvas.drawPath(lp, line)

        // Current line + label
        val cy = y(cur, pad, h)
        canvas.drawLine(pad, cy, w - 6f, cy, dash)
        canvas.drawText("$cur dBm", w - 8f, cy - 5f, val2)
    }
}
