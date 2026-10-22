package app.oddech.fala

import android.os.SystemClock
import kotlin.math.floor

/**
 * Zegar fazy oddechu oparty na czasie systemowym - używany, gdy nie gra dźwięk
 * (sama wibracja). Faza 0..1: 0 - 0,5 wdech, 0,5 - 1 wydech.
 * Zmiana rytmu w trakcie działa płynnie, bez skoku fazy.
 */
class BreathClock(private val intervalMs: () -> Int) {
    private var cum = 0.0
    private var last = SystemClock.elapsedRealtime()

    @Synchronized
    fun phase(): Double {
        val now = SystemClock.elapsedRealtime()
        val dt = now - last
        last = now
        cum += dt / (2.0 * intervalMs().coerceIn(1000, 20000))
        return cum - floor(cum)
    }
}
