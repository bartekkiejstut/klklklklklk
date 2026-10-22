package app.oddech.fala

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Delikatne sygnały dźwiękowe:
 *  - początek wdechu: dwa miękkie dźwięki w górę (G4 -> C5),
 *  - początek wydechu: dwa miękkie dźwięki w dół (C5 -> G4).
 * Dźwięki są niskie, o łagodnym ataku i długim wybrzmiewaniu (bez ostrych tonów).
 */
object Cues {
    private const val SR = 44100
    private const val TOTAL_SEC = 1.1
    private const val NOTE_GAP_SEC = 0.24

    private val handler = Handler(Looper.getMainLooper())

    private val inhaleData: ShortArray by lazy { build(392.0, 523.25) }
    private val exhaleData: ShortArray by lazy { build(523.25, 392.0) }

    /** inhale = true: sygnał wdechu (w górę), false: sygnał wydechu (w dół). */
    fun play(inhale: Boolean, volumePercent: Int) {
        try {
            val data = if (inhale) inhaleData else exhaleData
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            val v = volumePercent.coerceIn(1, 100) / 100f
            track.setVolume(v * v)
            track.play()
            handler.postDelayed({ releaseQuietly(track) }, 1600L)
        } catch (e: Exception) {
            // brak sygnału nie powinien przerywać metronomu
        }
    }

    /** Odtwarza próbkę obu sygnałów: najpierw wdech, po chwili wydech. */
    fun preview(volumePercent: Int) {
        play(true, volumePercent)
        handler.postDelayed({ play(false, volumePercent) }, 1300L)
    }

    private fun releaseQuietly(track: AudioTrack) {
        try {
            track.stop()
        } catch (e: Exception) {
        }
        try {
            track.release()
        } catch (e: Exception) {
        }
    }

    private fun build(f1: Double, f2: Double): ShortArray {
        val total = (SR * TOTAL_SEC).toInt()
        val out = FloatArray(total)
        addNote(out, f1, 0.0)
        addNote(out, f2, NOTE_GAP_SEC)

        // Krótkie wyciszenie końcówki, żeby nie było trzasku.
        val tail = (SR * 0.05).toInt()
        for (i in 0 until tail) {
            out[total - 1 - i] *= i.toFloat() / tail
        }
        return ShortArray(total) { (out[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    }

    private fun addNote(out: FloatArray, freq: Double, startSec: Double) {
        val start = (startSec * SR).toInt()
        val n = out.size - start
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val attack = 0.5 - 0.5 * cos(PI * minOf(1.0, t / 0.05)) // miękki atak 50 ms
            val env = attack * exp(-t / 0.20)                       // łagodne wybrzmiewanie
            val tone = sin(2.0 * PI * freq * t) + 0.2 * sin(2.0 * PI * 2.0 * freq * t)
            out[start + i] += (tone * env * 0.45).toFloat()
        }
    }
}
