package app.raah

import android.app.Application
import android.content.Context
import app.raah.ride.RideEngine

class RaahApp : Application() {
    val engine by lazy { RideEngine(this) }

    companion object {
        fun engine(context: Context) = (context.applicationContext as RaahApp).engine
    }
}
