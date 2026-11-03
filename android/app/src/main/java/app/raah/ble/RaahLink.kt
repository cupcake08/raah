package app.raah.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import app.raah.proto.NavProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.UUID

/**
 * GATT client for the Raah device. Android allows one GATT operation at a time,
 * so every write goes through a queue. Small, latest-wins messages (turn state,
 * pose) jump ahead of bulk transfers (maps, icons), so a map upload never delays
 * the turn arrow.
 */
@SuppressLint("MissingPermission")   // callers check BLUETOOTH_CONNECT / BLUETOOTH_SCAN first
class RaahLink(private val context: Context) {

    sealed interface State {
        data object Off : State
        data object Scanning : State
        data class Connecting(val name: String) : State
        data class Ready(val name: String, val address: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state
    private val _status = MutableStateFlow<NavProtocol.Status?>(null)
    val status: StateFlow<NavProtocol.Status?> = _status

    /** Called on the main thread once the link is ready (send time and settings here). */
    var onReady: (() -> Unit)? = null

    /** Largest ATT write payload for the negotiated MTU. */
    var maxWrite = 20
        private set

    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var wanted = false
    private var device: BluetoothDevice? = null
    private var chars = emptyMap<UUID, BluetoothGattCharacteristic>()

    private class Op(val uuid: UUID, val value: ByteArray, val noResponse: Boolean, val key: String? = null)

    private val urgent = ArrayDeque<Op>()
    private val bulk = ArrayDeque<Op>()
    private var busy = false

    fun start(rememberedAddress: String?) {
        wanted = true
        val a = adapter ?: return
        if (!a.isEnabled) return
        if (gatt != null) return
        val remembered = rememberedAddress?.let { runCatching { a.getRemoteDevice(it) }.getOrNull() }
        if (remembered != null) connect(remembered, autoConnect = true) else scan()
    }

    fun stop() {
        wanted = false
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        reset()
        _state.value = State.Off
    }

    /** Latest-wins: an unsent message with the same key is replaced. */
    fun sendUrgent(uuid: UUID, value: ByteArray, key: String) {
        main.post {
            urgent.removeIf { it.key == key }
            urgent.addLast(Op(uuid, value, noResponse = uuid == NavProtocol.MAP, key = key))
            pump()
        }
    }

    fun sendCtrl(value: ByteArray) {
        main.post {
            urgent.addLast(Op(NavProtocol.CTRL, value, noResponse = false))
            pump()
        }
    }

    fun sendBulk(uuid: UUID, values: List<ByteArray>) {
        main.post {
            values.forEach { bulk.addLast(Op(uuid, it, noResponse = true)) }
            pump()
        }
    }

    val ready get() = _state.value is State.Ready

    // ---- scanning ----

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            connect(result.device, autoConnect = false)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            main.postDelayed({ if (wanted && gatt == null) scan() }, 5000)
        }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        _state.value = State.Scanning
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NavProtocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
        // Android throttles long scans; restart every 25 s until something shows up.
        main.postDelayed({ if (_state.value == State.Scanning) { stopScan(); scan() } }, 25_000)
    }

    private fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    // ---- connection ----

    private fun connect(d: BluetoothDevice, autoConnect: Boolean) {
        device = d
        _state.value = State.Connecting(d.name ?: "Raah")
        gatt = d.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun reset() {
        urgent.clear()
        bulk.clear()
        busy = false
        chars = emptyMap()
        maxWrite = 20
        _status.value = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    g.requestMtu(247)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "disconnected ($status)")
                    reset()
                    g.close()
                    if (gatt == g) gatt = null
                    val d = device
                    when {
                        !wanted -> _state.value = State.Off
                        // Android reconnects in the background as soon as the device advertises again.
                        d != null -> connect(d, autoConnect = true)
                        else -> scan()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                maxWrite = (if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23) - 3
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val svc = g.getService(NavProtocol.SERVICE)
                if (svc == null) {
                    Log.w(TAG, "not a Raah device")
                    g.disconnect()
                    return@post
                }
                chars = svc.characteristics.associateBy { it.uuid }
                g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                chars[NavProtocol.STATUS]?.let { st ->
                    g.setCharacteristicNotification(st, true)
                    val cccd = st.getDescriptor(NavProtocol.CCCD)
                    val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    busy = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, on) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = on
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
                _state.value = State.Ready(g.device.name ?: "Raah", g.device.address)
                onReady?.invoke()
                pump()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                // Notifications are on; read STATUS once for the current battery and map id.
                busy = chars[NavProtocol.STATUS]?.let { g.readCharacteristic(it) } ?: false
                pump()
            }
        }

        @Deprecated("Called below API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            onCharacteristicRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post {
                if (c.uuid == NavProtocol.STATUS) _status.value = NavProtocol.decodeStatus(value)
                busy = false
                pump()
            }
        }

        @Deprecated("Called below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(g, c, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (c.uuid == NavProtocol.STATUS) _status.value = NavProtocol.decodeStatus(value) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "write ${c.uuid} failed: $status")
                busy = false
                pump()
            }
        }
    }

    private fun pump() {
        if (busy) return
        val g = gatt ?: return
        if (_state.value !is State.Ready) return
        val op = urgent.pollFirst() ?: bulk.pollFirst() ?: return
        val c = chars[op.uuid] ?: return pump()
        val type = if (op.noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        busy = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, op.value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = op.value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!busy) {
            // The stack is momentarily full; retry this op shortly.
            (if (op.key != null || op.uuid == NavProtocol.CTRL) urgent else bulk).addFirst(op)
            main.postDelayed({ pump() }, 20)
        }
    }

    companion object {
        private const val TAG = "RaahLink"
    }
}
