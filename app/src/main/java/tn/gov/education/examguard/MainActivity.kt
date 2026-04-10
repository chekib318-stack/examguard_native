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

    // device map: MAC → ScannedDevice
    private val deviceMap = mutableMapOf<String, ScannedDevice>()
    private lateinit var listAdapter: DeviceListAdapter

    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnScan: Button
    private lateinit var recycler: RecyclerView
    private lateinit var tvBtState: TextView

    companion object {
        const val PERM_CODE = 101
        const val SCAN_PERIOD_MS = 4000L
        const val RESTART_DELAY_MS = 2000L
        const val STALE_MS = 15000L
    }

    // ── Classic BT receiver ───────────────────────────────────────
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (dev != null && rssi > -127) addDevice(dev.address, rssi, dev.name, "Classic")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    handler.postDelayed({ if (isScanning) startClassicScan() }, RESTART_DELAY_MS)
                }
            }
        }
    }

    // ── BLE scan callback ─────────────────────────────────────────
    private val bleCb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: result.device.name?.takeIf { it.isNotBlank() }
                ?: ""
            addDevice(result.device.address, result.rssi, name, "BLE")
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanCallback.CALLBACK_TYPE_ALL_MATCHES, it) }
        }
        override fun onScanFailed(code: Int) {
            runOnUiThread { tvStatus.text = "BLE فشل (كود $code)" }
        }
    }

    private fun addDevice(mac: String, rssi: Int, name: String?, proto: String) {
        val now = System.currentTimeMillis()
        val existing = deviceMap[mac]
        deviceMap[mac] = ScannedDevice(
            mac       = mac,
            name      = name?.takeIf { it.isNotBlank() } ?: existing?.name ?: "",
            rssi      = rssi,
            protocol  = proto,
            lastSeen  = now,
            firstSeen = existing?.firstSeen ?: now
        )
        runOnUiThread { refreshList() }
    }

    private fun refreshList() {
        val now = System.currentTimeMillis()
        deviceMap.entries.removeIf { now - it.value.lastSeen > STALE_MS }
        val sorted = deviceMap.values.sortedByDescending { it.rssi }
        listAdapter.update(sorted)
        tvCount.text = "${sorted.size} جهاز"
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

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(classicReceiver, filter)

        btnScan.setOnClickListener {
            if (isScanning) stopScan() else requestPermissions()
        }
        requestPermissions()
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
            tvBtState.text = "⚠️ البلوتوث مُغلق — فعّله أولاً"
            tvBtState.visibility = View.VISIBLE
            return
        }
        tvBtState.visibility = View.GONE
        isScanning = true
        deviceMap.clear()
        btnScan.text = "⏹ إيقاف المسح"
        tvStatus.text = "جارٍ المسح..."

        startBleScan()
        startClassicScan()

        // Stale cleanup every 5s
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isScanning) {
                    refreshList()
                    handler.postDelayed(this, 5000)
                }
            }
        }, 5000)
    }

    private fun startBleScan() {
        bleScanner = btAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner?.startScan(null, settings, bleCb)
        } catch (e: SecurityException) {
            tvStatus.text = "صلاحيات BLE مرفوضة"
        }
        // Stop and restart BLE every SCAN_PERIOD_MS
        handler.postDelayed({
            if (isScanning) {
                try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
                handler.postDelayed({ if (isScanning) startBleScan() }, 500)
            }
        }, SCAN_PERIOD_MS)
    }

    private fun startClassicScan() {
        try {
            btAdapter?.cancelDiscovery()
            btAdapter?.startDiscovery()
        } catch (e: SecurityException) {}
    }

    private fun stopScan() {
        isScanning = false
        try { bleScanner?.stopScan(bleCb) } catch (_: Exception) {}
        try { btAdapter?.cancelDiscovery() } catch (_: Exception) {}
        btnScan.text = "▶ بدء المسح"
        tvStatus.text = "موقوف — ${deviceMap.size} جهاز مرصود"
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        if (!isScanning) startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
    }
}

// ─── Data model ───────────────────────────────────────────────────────────────
data class ScannedDevice(
    val mac: String,
    val name: String,
    val rssi: Int,
    val protocol: String,
    val lastSeen: Long,
    val firstSeen: Long
) {
    val displayName: String get() = name.ifBlank { mac.takeLast(8) }
    val distanceM: Double get() {
        return Math.pow(10.0, (-59.0 - rssi) / (10.0 * 2.7))
    }
    val distanceStr: String get() = when {
        distanceM < 1   -> "< 1 م"
        distanceM < 10  -> "%.1f م".format(distanceM)
        else            -> "%.0f م".format(distanceM)
    }
    val signalPercent: Int get() = ((rssi + 95.0) / 65.0 * 100).toInt().coerceIn(0, 100)
    val zoneColor: Int get() = when {
        rssi >= -45 -> 0xFFCC0000.toInt()
        rssi >= -55 -> 0xFFFF4400.toInt()
        rssi >= -65 -> 0xFFFF8800.toInt()
        rssi >= -75 -> 0xFFFFBB00.toInt()
        else        -> 0xFF33AA55.toInt()
    }
    val zoneLabel: String get() = when {
        rssi >= -45 -> "🔴 خطر شديد"
        rssi >= -55 -> "🔴 فتش الآن"
        rssi >= -65 -> "🟠 تحذير"
        rssi >= -75 -> "🟡 يقظة"
        else        -> "🟢 بعيد"
    }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────
class DeviceListAdapter(
    private val onDeviceClick: (ScannedDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.VH>() {

    private var items = listOf<ScannedDevice>()

    fun update(newItems: List<ScannedDevice>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName     : TextView     = view.findViewById(R.id.tvDevName)
        val tvMac      : TextView     = view.findViewById(R.id.tvDevMac)
        val tvRssi     : TextView     = view.findViewById(R.id.tvDevRssi)
        val tvDist     : TextView     = view.findViewById(R.id.tvDevDist)
        val tvZone     : TextView     = view.findViewById(R.id.tvDevZone)
        val tvProto    : TextView     = view.findViewById(R.id.tvDevProto)
        val progressBar: ProgressBar  = view.findViewById(R.id.pbSignal)
        val btnTrack   : Button       = view.findViewById(R.id.btnTrack)
    }

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(vh: VH, pos: Int) {
        val d = items[pos]
        vh.tvName.text     = d.displayName
        vh.tvMac.text      = d.mac
        vh.tvRssi.text     = "${d.rssi} dBm"
        vh.tvDist.text     = d.distanceStr
        vh.tvZone.text     = d.zoneLabel
        vh.tvProto.text    = d.protocol
        vh.progressBar.progress = d.signalPercent
        vh.progressBar.progressTintList =
            android.content.res.ColorStateList.valueOf(d.zoneColor)
        vh.tvRssi.setTextColor(d.zoneColor)
        vh.tvZone.setTextColor(d.zoneColor)
        vh.btnTrack.setOnClickListener { onDeviceClick(d) }
        vh.itemView.setOnClickListener { onDeviceClick(d) }
    }
}
