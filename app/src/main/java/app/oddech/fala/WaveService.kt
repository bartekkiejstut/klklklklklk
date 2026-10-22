package app.oddech.fala

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class WaveService : Service() {

    companion object {
        const val ACTION_START = "app.oddech.fala.START"
        const val ACTION_STOP = "app.oddech.fala.STOP"

        const val PREFS = "fala_prefs"
        const val KEY_INTERVAL = "interval_ms"
        const val KEY_VOLUME = "volume_percent"
        const val KEY_MODE = "mode"
        const val KEY_AMPLITUDE = "vib_amplitude"
        const val KEY_PULSES = "vib_pulses"
        const val KEY_CUE = "cue_on"
        const val KEY_CUE_VOLUME = "cue_volume"

        const val MODE_WAVE = 0
        const val MODE_VIB = 1
        const val MODE_BOTH = 2

        const val DEFAULT_INTERVAL_MS = 5500
        const val DEFAULT_VOLUME = 60
        const val DEFAULT_MODE = MODE_WAVE
        const val DEFAULT_AMPLITUDE = 255
        const val DEFAULT_PULSES = 1
        const val DEFAULT_CUE_VOLUME = 50

        private const val CHANNEL_ID = "fala_channel"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        // Wywoływany zawsze w wątku głównym.
        var listener: ((Boolean) -> Unit)? = null

        @Volatile
        private var phaseFn: (() -> Double?)? = null

        /** Aktualnie słyszana faza cyklu (0..1) albo null, gdy metronom nie gra. */
        fun currentPhase(): Double? = if (isRunning) phaseFn?.invoke() else null
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator
    private var wakeLock: PowerManager.WakeLock? = null

    private var synth: WaveSynth? = null
    private var clock: BreathClock? = null
    private var watcher: Thread? = null

    @Volatile
    private var watchActive = false
    private var appliedMode = -1

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        vibrator = Haptics.getVibrator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        val mode = prefs.getInt(KEY_MODE, DEFAULT_MODE).coerceIn(0, 2)
        val cueOn = prefs.getBoolean(KEY_CUE, false)

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundType(mode != MODE_VIB || cueOn)
        )
        acquireWakeLock()

        // Pierwszy start albo zmiana trybu w trakcie działania.
        if (mode != appliedMode) rebuild(mode)

        isRunning = true
        listener?.invoke(true)
        return START_NOT_STICKY
    }

    private fun foregroundType(audioOn: Boolean): Int = when {
        audioOn -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
    }

    private fun rebuild(mode: Int) {
        stopComponents()

        val waveOn = mode != MODE_VIB
        val vibOn = mode != MODE_WAVE

        val newClock = BreathClock { prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MS) }
        clock = newClock

        val s: WaveSynth? = if (waveOn) {
            WaveSynth(
                intervalMs = { prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MS) },
                volumePercent = { prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME) }
            )
        } else {
            null
        }
        s?.start()
        synth = s

        // Źródło fazy: przy fali - to, co faktycznie słychać; bez fali - zegar systemowy.
        val source: () -> Double? = if (s != null) s::currentPhase else newClock::phase
        phaseFn = source

        // Wątek pilnujący granic faz: na początku wdechu i wydechu uruchamia
        // wibrację (jeśli tryb ją zawiera) oraz sygnał dźwiękowy (jeśli włączony).
        startWatcher(source, vibOn)
        appliedMode = mode
    }

    private fun startWatcher(source: () -> Double?, vibOn: Boolean) {
        watchActive = true
        val v = vibrator
        val thread = Thread({
            var lastHalf = -1
            var lastTriggerAt = 0L
            while (watchActive) {
                val p = source()
                if (p == null) {
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }

                // Początek wdechu (faza 0) i początek wydechu (faza 0,5).
                val half = if (p < 0.5) 0 else 1
                val now = SystemClock.elapsedRealtime()
                if (half != lastHalf && (lastHalf == -1 || now - lastTriggerAt > 2000L)) {
                    lastHalf = half
                    lastTriggerAt = now
                    if (vibOn) {
                        Haptics.pulse(
                            v,
                            prefs.getInt(KEY_AMPLITUDE, DEFAULT_AMPLITUDE),
                            prefs.getInt(KEY_PULSES, DEFAULT_PULSES)
                        )
                    }
                    if (prefs.getBoolean(KEY_CUE, false)) {
                        Cues.play(half == 0, prefs.getInt(KEY_CUE_VOLUME, DEFAULT_CUE_VOLUME))
                    }
                }

                val interval = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MS).coerceIn(1000, 20000)
                val boundary = if (p < 0.5) 0.5 else 1.0
                val remainingMs = ((boundary - p) * 2.0 * interval).toLong()
                val nap = if (remainingMs > 40L) minOf(remainingMs - 25L, 200L) else 2L
                try {
                    Thread.sleep(nap)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "phase-watcher")
        watcher = thread
        thread.start()
    }

    private fun stopComponents() {
        watchActive = false
        watcher?.interrupt()
        watcher = null
        try {
            vibrator.cancel()
        } catch (e: Exception) {
        }
        synth?.stop()
        synth = null
        clock = null
        phaseFn = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fala:metronome").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L) // maks. 8 h
        }
    }

    private fun stopEverything() {
        stopComponents()
        appliedMode = -1
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Oddech koherentny",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Stałe powiadomienie podczas działania metronomu"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WaveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Oddech koherentny")
            .setContentText("Metronom oddechowy działa")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopComponents()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        isRunning = false
        listener?.invoke(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
