package app.raah.ride

import android.content.Context
import app.raah.proto.NavProtocol.BacklightMode
import app.raah.route.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val imperial: Boolean = false,
    val use24h: Boolean = false,
    val backlight: BacklightMode = BacklightMode.SMART_DIM,
    val brightness: Int = 100,
    val vehicle: Vehicle = Vehicle.SCOOTER,
    /** Last device connected, so the app reconnects without scanning. */
    val deviceAddress: String? = null,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("raah", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Settings> = _flow
    val value get() = _flow.value

    fun update(f: (Settings) -> Settings) {
        val s = f(_flow.value)
        prefs.edit()
            .putBoolean("imperial", s.imperial)
            .putBoolean("use24h", s.use24h)
            .putString("backlight", s.backlight.name)
            .putInt("brightness", s.brightness)
            .putString("vehicle", s.vehicle.name)
            .putString("device", s.deviceAddress)
            .apply()
        _flow.value = s
    }

    private fun load() = Settings(
        imperial = prefs.getBoolean("imperial", false),
        use24h = prefs.getBoolean("use24h", false),
        backlight = runCatching { BacklightMode.valueOf(prefs.getString("backlight", "")!!) }.getOrDefault(BacklightMode.SMART_DIM),
        brightness = prefs.getInt("brightness", 100),
        vehicle = runCatching { Vehicle.valueOf(prefs.getString("vehicle", "")!!) }.getOrDefault(Vehicle.SCOOTER),
        deviceAddress = prefs.getString("device", null),
    )
}
