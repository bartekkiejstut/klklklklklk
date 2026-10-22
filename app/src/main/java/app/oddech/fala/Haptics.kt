package app.oddech.fala

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    private const val SINGLE_MS = 190L
    private const val DOUBLE_PULSE_MS = 150L
    private const val DOUBLE_GAP_MS = 110L

    fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** pulses = 1: pojedynczy impuls, pulses = 2: podwójny impuls. */
    @Suppress("DEPRECATION")
    fun pulse(vibrator: Vibrator, amplitude: Int, pulses: Int) {
        val hasAmp = vibrator.hasAmplitudeControl()
        val amp = amplitude.coerceIn(1, 255)
        val effect = if (pulses >= 2) {
            val timings = longArrayOf(0, DOUBLE_PULSE_MS, DOUBLE_GAP_MS, DOUBLE_PULSE_MS)
            if (hasAmp) {
                VibrationEffect.createWaveform(timings, intArrayOf(0, amp, 0, amp), -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
        } else {
            if (hasAmp) {
                VibrationEffect.createOneShot(SINGLE_MS, amp)
            } else {
                VibrationEffect.createOneShot(SINGLE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            }
        }
        // USAGE_ALARM: wibracja działa przy zgaszonym ekranie i nie jest
        // wyciszana jak "dotyk/klawiatura".
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        vibrator.vibrate(effect, attrs)
    }
}
