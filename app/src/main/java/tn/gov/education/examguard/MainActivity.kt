package tn.gov.education.examguard

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private var btAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // mac → ScannedDevice
    private val deviceMap = mutableMapOf<String, ScannedDevice>()
    private lateinit var listAdapter: DeviceListAdapter

    private lateinit var tvStatus  : TextView
    private lateinit var tvCount   : TextView
    private lateinit var btnScan   : Button
    private lateinit var recycler  : RecyclerView
    private lateinit var tvBtState : TextView

    companion object {
        const val PERM_CODE    = 101
        const val STALE_MS     = 10000L   // remove device after 10s no signal
        const val UI_REFRESH_MS = 800L    // refresh list every 800ms
        const val BLE_RESTART_MS = 2000L  // restart BLE scan every 2s
    }

    // ── Classic BT receiver ────────────────────────────────────────
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (dev != null && rssi > -127)
                        addDevice(dev.address, rssi, dev.name, "Classic BR/EDR")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Restart immediately — no delay
                    if (isScanning) startClassicScan()
                }
            }
        }
    }

    // ── BLE scan — LOW_LATENCY for maximum responsiveness ─────────
    private val bleCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: result.device.name?.takeIf { it.isNotBlank() }
                ?: ""
            addDevice(result.device.address, result.rssi, name, "BLE")
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(1, it) }
        }
        override fun onScanFailed(code: Int) {
            runOnUiThread { tvStatus.text = "فشل BLE ($code) — Classic فقط" }
        }
    }

    private fun addDevice(mac: String, rssi: Int, name: String?, proto: String) {
        val now = System.currentTimeMillis()
        val old = deviceMap[mac]
        deviceMap[mac] = ScannedDevice(
            mac       = mac,
            name      = name?.takeIf { it.isNotBlank() } ?: old?.name ?: "",
            rssi      = rssi,
            protocol  = proto,
            lastSeen  = now,
            firstSeen = old?.firstSeen ?: now
        )
    }

    // ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        tvStatus  = findViewById(R.id.tvStatus)
        tvCount   = findViewById(R.id.tvCount)
        btnScan   = findViewById(R.id.btnScan)
        recycler  = findViewById(R.id.recycler)
        tvBtState = findViewById(R.id.tvBtState)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter

        listAdapter = DeviceListAdapter { device ->
            val intent = Intent(this, TrackActivity::class.java)
            intent.putExtra("mac",   device.mac)
            intent.putExtra("name",  device.name)
            intent.putExtra("proto", device.protocol)
            stopScan()
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = listAdapter
        recycler.itemAnimator = null  // Disable animations for faster refresh

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(classicReceiver, filter)

        btnScan.setOnClickListener {
            if (isScanning) stopScan() else requestPermissions()
        }
        requestPermissions()

        // Periodic UI refresh (independent of scan results)
        startUiRefreshLoop()
    }

    // Separate UI refresh loop — updates list + removes stale devices
    private fun startUiRefreshLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isScanning) refreshList()
                handler.postDelayed(this, UI_REFRESH_MS)
            }
        }, UI_REFRESH_MS)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val denied = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) startScan()
        else ActivityCompat.requestPermissions(this, denied.toTypedArray(), PERM_CODE)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == PERM_CODE) startScan()
    }

    private fun startScan() {
        if (btAdapter?.isEnabled != true) {
            tvBtState.text = "⚠️ فعّل البلوتوث أولاً"
            tvBtState.visibility = View.VISIBLE
            return
        }
        tvBtState.visibility = View.GONE
        isScanning = true
        deviceMap.clear()
        btnScan.text = "⏹ إيقاف"
        tvStatus.text = "جارٍ المسح..."

        startBleScan()
        startClassicScan()
    }

    private fun startBleScan() {
        bleScanner = btAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try { bleScanner?.startScan(null, settings, bleCb) } catch (_: Exception) {}

        // Restart BLE scan every BLE_RESTART_MS to avoid throttling
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isScanning) return
                try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
                handler.postDelayed({
                    if (isScanning) {
                        try { bleScanner?.startScan(null, settings, bleCb)
                        } catch (_: Exception) {}
                    }
                }, 100)
                handler.postDelayed(this, BLE_RESTART_MS)
            }
        }, BLE_RESTART_MS)
    }

    private fun startClassicScan() {
        try { btAdapter?.cancelDiscovery(); btAdapter?.startDiscovery() } catch (_: Exception) {}
    }

    private fun refreshList() {
        val now = System.currentTimeMillis()
        deviceMap.entries.removeIf { now - it.value.lastSeen > STALE_MS }
        val sorted = deviceMap.values.sortedByDescending { it.rssi }
        listAdapter.update(sorted)
        tvCount.text = "${sorted.size} جهاز"
        tvStatus.text = if (sorted.isEmpty()) "جارٍ المسح..."
                        else "أقرب: ${sorted.first().rssi} dBm — ${sorted.first().distanceStr}"
    }

    private fun stopScan() {
        isScanning = false
        try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
        try { btAdapter?.cancelDiscovery() } catch (_: Exception) {}
        btnScan.text = "▶ بدء المسح"
        tvStatus.text = "موقوف"
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() { super.onResume(); if (!isScanning) startScan() }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
    }
}

// ─── Data model ───────────────────────────────────────────────────────────────
data class ScannedDevice(
    val mac: String, val name: String, val rssi: Int,
    val protocol: String, val lastSeen: Long, val firstSeen: Long
) {
    val displayName get() = name.ifBlank { mac.takeLast(8) }
    private val distM get() = Math.pow(10.0, (-59.0 - rssi) / (10.0 * 2.7))
    val distanceStr  get() = when {
        distM < 0.5 -> "< 0.5 م"
        distM < 10  -> "%.1f م".format(distM)
        else        -> "%.0f م".format(distM)
    }
    val signalPercent get() = ((rssi + 95.0) / 65.0 * 100).toInt().coerceIn(0, 100)
    val zoneColor get() = when {
        rssi >= -45 -> 0xFFAA0000.toInt()
        rssi >= -55 -> 0xFFDD2200.toInt()
        rssi >= -65 -> 0xFFFF7700.toInt()
        rssi >= -78 -> 0xFFFFBB00.toInt()
        else        -> 0xFF22AA55.toInt()
    }
    val zoneLabel get() = when {
        rssi >= -45 -> "🚨 فتش الآن"
        rssi >= -55 -> "🔴 < 1م"
        rssi >= -65 -> "🟠 ~ 2م"
        rssi >= -78 -> "🟡 ~ 5م"
        else        -> "🟢 بعيد"
    }
}

// ─── Adapter ──────────────────────────────────────────────────────────────────
class DeviceListAdapter(
    private val onClick: (ScannedDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.VH>() {

    private var items = listOf<ScannedDevice>()

    fun update(newItems: List<ScannedDevice>) { items = newItems; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName = v.findViewById<TextView>(R.id.tvDevName)
        val tvMac  = v.findViewById<TextView>(R.id.tvDevMac)
        val tvRssi = v.findViewById<TextView>(R.id.tvDevRssi)
        val tvDist = v.findViewById<TextView>(R.id.tvDevDist)
        val tvZone = v.findViewById<TextView>(R.id.tvDevZone)
        val tvProto= v.findViewById<TextView>(R.id.tvDevProto)
        val pb     = v.findViewById<ProgressBar>(R.id.pbSignal)
        val btn    = v.findViewById<Button>(R.id.btnTrack)
    }

    override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
        android.view.LayoutInflater.from(p.context).inflate(R.layout.item_device, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(vh: VH, pos: Int) {
        val d = items[pos]
        vh.tvName.text = d.displayName
        vh.tvMac.text  = d.mac
        vh.tvRssi.text = "${d.rssi} dBm"
        vh.tvDist.text = d.distanceStr
        vh.tvZone.text = d.zoneLabel
        vh.tvProto.text = d.protocol
        vh.pb.progress = d.signalPercent
        vh.pb.progressTintList = android.content.res.ColorStateList.valueOf(d.zoneColor)
        vh.tvRssi.setTextColor(d.zoneColor)
        vh.tvZone.setTextColor(d.zoneColor)
        vh.btn.setOnClickListener { onClick(d) }
        vh.itemView.setOnClickListener { onClick(d) }
    }
}
