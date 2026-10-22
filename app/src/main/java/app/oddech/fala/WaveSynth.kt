package app.oddech.fala

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generuje na żywo miękki szum fali morza.
 *
 * Cykl = wdech + wydech, każda faza trwa "intervalMs".
 * Przez cały wdech dźwięk narasta, w ostatniej chwili wdechu jest najgłośniejszy,
 * a od początku wydechu od tego samego poziomu stopniowo cichnie przez cały wydech.
 * Narastanie jest liczone w skali decybelowej (zgodnej ze słuchem), więc
 * zmiana głośności jest równomiernie słyszalna przez całe 5-6 sekund.
 *
 * Instancja jest jednorazowa: start() -> stop().
 * Jeśli durationMs > 0, dźwięk sam się wyłączy po tym czasie (podgląd).
 */
class WaveSynth(
    private val intervalMs: () -> Int,
    private val volumePercent: () -> Int,
    private val durationMs: Long = 0L
) {
    companion object {
        private const val SR = 44100
        private const val CHUNK_FRAMES = 2048
        private const val FADE_SECONDS = 0.30f
        private const val DEPTH = 3.7f // ok. 32 dB zakresu między ciszą a szczytem fali
        private const val TWO_PI_F = 6.2831855f
    }

    @Volatile private var started = false
    @Volatile private var finished = false
    @Volatile private var stopRequested = false

    // Dane do synchronizacji animacji koła z dźwiękiem.
    private class Snap(val written: Long, val cum: Double, val d: Double)

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var snap = Snap(0L, 0.0, 0.0)

    val isActive: Boolean
        get() = started && !finished && !stopRequested

    fun start() {
        if (started) return
        started = true
        Thread({ render() }, "wave-synth").start()
    }

    fun stop() {
        stopRequested = true
    }

    /**
     * Faza cyklu aktualnie SŁYSZANA (0..1): 0 - 0,5 wdech, 0,5 - 1 wydech.
     * Uwzględnia dźwięk, który jest już wygenerowany, ale jeszcze nie zagrany.
     */
    fun currentPhase(): Double? {
        val tr = audioTrack ?: return null
        if (!started || finished || stopRequested) return null
        val head = try {
            tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (e: Exception) {
            return null
        }
        val sn = snap
        val pending = (sn.written - head).coerceIn(0L, SR.toLong())
        var c = sn.cum - pending * sn.d
        if (c < 0.0) c = 0.0
        return c - floor(c)
    }

    private fun render() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(SR, channelMask, encoding)
        val bufBytes = maxOf(minBuf, SR) // ok. 0,25 s bufora

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(SR)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            finished = true
            return
        }
        audioTrack = track

        // Generator szumu (xorshift) - osobny dla lewego i prawego kanału,
        // dzięki temu fala brzmi szeroko w słuchawkach.
        var seedL = 0x2545F491
        var seedR = 0x1B873593
        var l1 = 0f
        var l2 = 0f
        var r1 = 0f
        var r2 = 0f

        var cum = 0.0            // łączna liczba przebytych cykli (wdech + wydech)
        var gain = -1f
        var master = 0f          // wejście / wyjście z dźwięku (fade)
        var t = 0L
        var frames = 0L
        val limitFrames = if (durationMs > 0) durationMs * SR / 1000 else Long.MAX_VALUE
        val fadeStep = 1f / (FADE_SECONDS * SR)
        val buf = ShortArray(CHUNK_FRAMES * 2)

        try {
            track.play()
            while (true) {
                if (frames >= limitFrames) stopRequested = true
                if (stopRequested && master <= 0f) break

                val interval = intervalMs().coerceIn(1000, 20000)
                val vol = volumePercent().coerceIn(1, 100) / 100f
                val targetGain = vol * vol * 0.9f
                if (gain < 0f) gain = targetGain
                // Cały cykl = 2 fazy, każda trwa "interval" ms.
                val dPhase = 1.0 / (2.0 * (interval / 1000.0) * SR)

                // Barwa (filtr) zależna od aktualnej głośności fali.
                val eChunk = (0.5 - 0.5 * cos(2.0 * PI * cum)).toFloat()
                val fc = 200f + 1300f * eChunk
                val alpha = 1f - exp(-TWO_PI_F * fc / SR)
                val norm = 1f / sqrt(alpha)

                for (i in 0 until CHUNK_FRAMES) {
                    // e: 0 -> 1 przez wdech (szczyt dokładnie na końcu wdechu),
                    //    1 -> 0 przez wydech (start dokładnie od poziomu szczytu).
                    val e = (0.5 - 0.5 * cos(2.0 * PI * cum)).toFloat()
                    val env = exp(-DEPTH * (1f - e))

                    // Delikatne "falowanie" powierzchni (zapętla się bez skoku co 100 s).
                    val ts = (t % 4410000L).toFloat() / SR
                    val mod = 1f +
                        0.10f * sin(TWO_PI_F * 0.31f * ts) +
                        0.06f * sin(TWO_PI_F * 0.83f * ts + 1.3f)

                    gain += 0.0008f * (targetGain - gain)

                    if (stopRequested) {
                        master -= fadeStep
                        if (master < 0f) master = 0f
                    } else if (master < 1f) {
                        master += fadeStep
                        if (master > 1f) master = 1f
                    }

                    seedL = seedL xor (seedL shl 13)
                    seedL = seedL xor (seedL ushr 17)
                    seedL = seedL xor (seedL shl 5)
                    seedR = seedR xor (seedR shl 13)
                    seedR = seedR xor (seedR ushr 17)
                    seedR = seedR xor (seedR shl 5)
                    val nl = seedL / 2147483648f
                    val nr = seedR / 2147483648f

                    l1 += alpha * (nl - l1)
                    l2 += alpha * (l1 - l2)
                    r1 += alpha * (nr - r1)
                    r2 += alpha * (r1 - r2)

                    val amp = gain * env * mod * master
                    val outL = (l2 * norm * amp).coerceIn(-1f, 1f)
                    val outR = (r2 * norm * amp).coerceIn(-1f, 1f)
                    buf[2 * i] = (outL * 32767f).toInt().toShort()
                    buf[2 * i + 1] = (outR * 32767f).toInt().toShort()

                    cum += dPhase
                    t++
                }

                track.write(buf, 0, buf.size)
                frames += CHUNK_FRAMES
                snap = Snap(frames, cum, dPhase)
            }

            track.stop()          // dogrywa resztę bufora (cichy ogon)
            Thread.sleep(400)
        } catch (e: Exception) {
            // ignorujemy - i tak kończymy
        } finally {
            audioTrack = null
            try {
                track.release()
            } catch (e: Exception) {
            }
            finished = true
        }
    }
}
