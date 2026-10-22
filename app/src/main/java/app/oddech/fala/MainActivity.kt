package app.oddech.fala

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {

    private val intervals = intArrayOf(5000, 5500, 6000)
    private val rhythmLabels = arrayOf("5 s", "5,5 s", "6 s")
    private val modeLabels = arrayOf("Fala", "Wibracja", "Fala + wibracja")

    private val rhythmButtons = ArrayList<Button>()
    private val modeButtons = ArrayList<Button>()

    private lateinit var startStop: Button
    private lateinit var rateLabel: TextView
    private lateinit var waveLabel: TextView
    private lateinit var vibLabel: TextView
    private lateinit var cueLabel: TextView
    private lateinit var waveGroup: LinearLayout
    private lateinit var vibGroup: LinearLayout
    private lateinit var cueSliderGroup: LinearLayout
    private lateinit var pulseButton: Button
    private lateinit var cueButton: Button
    private lateinit var breath: BreathView
    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator

    private var preview: WaveSynth? = null
    private var lastTestPulse = 0L

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(WaveService.PREFS, MODE_PRIVATE)
        vibrator = Haptics.getVibrator(this)
        // Klawisze głośności telefonu sterują głośnością multimediów.
        volumeControlStream = AudioManager.STREAM_MUSIC

        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        // Układ: przewijana lista ustawień + na dole stały przycisk START/STOP.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        // Android 15 wymusza edge-to-edge, więc odsuwamy treść od pasków systemowych.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                dp(24) + bars.left,
                dp(16) + bars.top,
                dp(24) + bars.right,
                dp(16) + bars.bottom
            )
            insets
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, lp(match, wrap))
        }
        root.addView(scroll, lp(match, 0).apply { weight = 1f })

        val title = TextView(this).apply {
            text = "Oddech koherentny"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        content.addView(title, lp(match, wrap).apply { topMargin = dp(4); bottomMargin = dp(12) })

        // --- 3 przyciski rytmu ---
        content.addView(
            selectorRow(rhythmLabels, 64, 20f, rhythmButtons) { i ->
                prefs.edit().putInt(WaveService.KEY_INTERVAL, intervals[i]).apply()
                refreshRhythm()
            },
            lp(match, wrap)
        )

        rateLabel = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
        }
        content.addView(rateLabel, lp(match, wrap).apply { topMargin = dp(6); bottomMargin = dp(10) })

        // --- Tryb: fala / wibracja / oba ---
        content.addView(
            selectorRow(modeLabels, 60, 14f, modeButtons) { i ->
                prefs.edit().putInt(WaveService.KEY_MODE, i).apply()
                refreshMode()
                if (WaveService.isRunning) startWaveService() // zastosuj od razu
            },
            lp(match, wrap)
        )

        // --- Kółko oddechowe ---
        breath = BreathView(this).apply {
            phaseProvider = { WaveService.currentPhase() }
        }
        content.addView(breath, lp(match, dp(220)).apply { topMargin = dp(10); bottomMargin = dp(10) })

        // --- Głośność fali ---
        waveGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        waveLabel = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val savedVol = prefs.getInt(WaveService.KEY_VOLUME, WaveService.DEFAULT_VOLUME).coerceIn(1, 100)
        val waveSeek = makeSeek(
            max = 99,
            progress = savedVol - 1,
            onChange = { prog, fromUser ->
                val vol = prog + 1
                updateWaveLabel(vol)
                if (fromUser) prefs.edit().putInt(WaveService.KEY_VOLUME, vol).apply()
            },
            onRelease = { prog ->
                prefs.edit().putInt(WaveService.KEY_VOLUME, prog + 1).apply()
                playPreview()
            }
        )
        waveGroup.addView(waveLabel, lp(match, wrap))
        waveGroup.addView(waveSeek, lp(match, dp(44)))
        content.addView(waveGroup, lp(match, wrap))
        updateWaveLabel(savedVol)

        // --- Siła wibracji + rodzaj impulsu ---
        vibGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vibLabel = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val savedAmp = prefs.getInt(WaveService.KEY_AMPLITUDE, WaveService.DEFAULT_AMPLITUDE).coerceIn(1, 255)
        val vibSeek = makeSeek(
            max = 254,
            progress = savedAmp - 1,
            onChange = { prog, fromUser ->
                val amp = prog + 1
                updateVibLabel(amp)
                if (fromUser) {
                    prefs.edit().putInt(WaveService.KEY_AMPLITUDE, amp).apply()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTestPulse > 450) {
                        lastTestPulse = now
                        testPulse(amp)
                    }
                }
            },
            onRelease = { prog ->
                val amp = prog + 1
                prefs.edit().putInt(WaveService.KEY_AMPLITUDE, amp).apply()
                lastTestPulse = SystemClock.elapsedRealtime()
                testPulse(amp)
            }
        )
        pulseButton = Button(this).apply {
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#555555"))
            setOnClickListener {
                val next = if (currentPulses() >= 2) 1 else 2
                prefs.edit().putInt(WaveService.KEY_PULSES, next).apply()
                refreshPulseButton()
                testPulse(currentAmplitude())
            }
        }
        vibGroup.addView(vibLabel, lp(match, wrap))
        vibGroup.addView(vibSeek, lp(match, dp(44)))
        vibGroup.addView(pulseButton, lp(match, dp(48)))
        content.addView(vibGroup, lp(match, wrap).apply { topMargin = dp(4) })
        updateVibLabel(savedAmp)

        // --- Sygnał dźwiękowy na początku wdechu i wydechu (opcja) ---
        cueButton = Button(this).apply {
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener {
                val on = !prefs.getBoolean(WaveService.KEY_CUE, false)
                prefs.edit().putBoolean(WaveService.KEY_CUE, on).apply()
                refreshCue()
                if (on) Cues.preview(currentCueVolume())
                if (WaveService.isRunning) startWaveService() // zaktualizuj typ usługi
            }
        }
        content.addView(cueButton, lp(match, dp(48)).apply { topMargin = dp(10) })

        cueSliderGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cueLabel = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val savedCue = currentCueVolume()
        val cueSeek = makeSeek(
            max = 99,
            progress = savedCue - 1,
            onChange = { prog, fromUser ->
                val vol = prog + 1
                updateCueLabel(vol)
                if (fromUser) prefs.edit().putInt(WaveService.KEY_CUE_VOLUME, vol).apply()
            },
            onRelease = { prog ->
                val vol = prog + 1
                prefs.edit().putInt(WaveService.KEY_CUE_VOLUME, vol).apply()
                Cues.preview(vol)
            }
        )
        cueSliderGroup.addView(cueLabel, lp(match, wrap))
        cueSliderGroup.addView(cueSeek, lp(match, dp(44)))
        content.addView(cueSliderGroup, lp(match, wrap).apply { topMargin = dp(4) })
        updateCueLabel(savedCue)

        // Odstęp na dole przewijanej części.
        content.addView(View(this), lp(match, dp(8)))

        // --- START / STOP (stały, zawsze widoczny) ---
        startStop = Button(this).apply {
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setOnClickListener { toggle() }
        }
        root.addView(startStop, lp(match, dp(96)).apply { topMargin = dp(10) })

        setContentView(root)
        refreshRhythm()
        refreshMode()
        refreshPulseButton()
        refreshCue()
        refreshStartButton()
    }

    override fun onStart() {
        super.onStart()
        WaveService.listener = { runOnUiThread { refreshStartButton() } }
        refreshStartButton()
        breath.startLoop()
    }

    override fun onStop() {
        breath.stopLoop()
        WaveService.listener = null
        preview?.stop()
        preview = null
        super.onStop()
    }

    // ---------- budowanie UI ----------

    private fun selectorRow(
        labels: Array<String>,
        heightDp: Int,
        textSp: Float,
        store: MutableList<Button>,
        onClick: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in labels.indices) {
            val b = Button(this).apply {
                text = labels[i]
                textSize = textSp
                isAllCaps = false
                setTextColor(Color.WHITE)
                setOnClickListener { onClick(i) }
            }
            store.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        return row
    }

    private fun highlight(buttons: List<Button>, selected: Int) {
        for (i in buttons.indices) {
            buttons[i].backgroundTintList = ColorStateList.valueOf(
                if (i == selected) Color.parseColor("#1E88E5") else Color.parseColor("#555555")
            )
        }
    }

    private fun makeSeek(
        max: Int,
        progress: Int,
        onChange: (Int, Boolean) -> Unit,
        onRelease: (Int) -> Unit
    ): SeekBar {
        return SeekBar(this).apply {
            this.max = max
            this.progress = progress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    onChange(progress, fromUser)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    onRelease(sb?.progress ?: 0)
                }
            })
        }
    }

    // ---------- akcje ----------

    // Krótki podgląd (ok. 2 s fali), żeby usłyszeć wybraną głośność.
    private fun playPreview() {
        if (WaveService.isRunning) return // podczas działania głośność słychać na żywo
        preview?.stop()
        preview = WaveSynth(
            intervalMs = { 1000 },
            volumePercent = { prefs.getInt(WaveService.KEY_VOLUME, WaveService.DEFAULT_VOLUME) },
            durationMs = 2000
        ).also { it.start() }
    }

    private fun testPulse(amp: Int) {
        Haptics.pulse(vibrator, amp, currentPulses())
    }

    private fun currentPulses(): Int =
        prefs.getInt(WaveService.KEY_PULSES, WaveService.DEFAULT_PULSES)

    private fun currentAmplitude(): Int =
        prefs.getInt(WaveService.KEY_AMPLITUDE, WaveService.DEFAULT_AMPLITUDE).coerceIn(1, 255)

    private fun currentCueVolume(): Int =
        prefs.getInt(WaveService.KEY_CUE_VOLUME, WaveService.DEFAULT_CUE_VOLUME).coerceIn(1, 100)

    private fun startWaveService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, WaveService::class.java).setAction(WaveService.ACTION_START)
        )
    }

    private fun toggle() {
        if (WaveService.isRunning) {
            startService(
                Intent(this, WaveService::class.java).setAction(WaveService.ACTION_STOP)
            )
        } else {
            preview?.stop()
            preview = null
            requestNotificationPermissionIfNeeded()
            startWaveService()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // ---------- odświeżanie widoku ----------

    private fun updateWaveLabel(vol: Int) {
        waveLabel.text = "Głośność fali: $vol%"
    }

    private fun updateVibLabel(amp: Int) {
        vibLabel.text = "Siła wibracji: $amp / 255"
    }

    private fun updateCueLabel(vol: Int) {
        cueLabel.text = "Głośność sygnału: $vol%"
    }

    private fun refreshPulseButton() {
        pulseButton.text = if (currentPulses() >= 2) "Impuls: podwójny" else "Impuls: pojedynczy"
    }

    private fun refreshCue() {
        val on = prefs.getBoolean(WaveService.KEY_CUE, false)
        cueButton.text = if (on) "Sygnał wdech / wydech: włączony" else "Sygnał wdech / wydech: wyłączony"
        cueButton.backgroundTintList = ColorStateList.valueOf(
            if (on) Color.parseColor("#1E88E5") else Color.parseColor("#555555")
        )
        cueSliderGroup.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun refreshRhythm() {
        val current = prefs.getInt(WaveService.KEY_INTERVAL, WaveService.DEFAULT_INTERVAL_MS)
        highlight(rhythmButtons, intervals.indexOf(current))
        val perMinute = 60000.0 / (2.0 * current)
        rateLabel.text = String.format("%.2f oddechu/min", perMinute).replace('.', ',')
    }

    private fun refreshMode() {
        val mode = prefs.getInt(WaveService.KEY_MODE, WaveService.DEFAULT_MODE).coerceIn(0, 2)
        highlight(modeButtons, mode)
        waveGroup.visibility = if (mode != WaveService.MODE_VIB) View.VISIBLE else View.GONE
        vibGroup.visibility = if (mode != WaveService.MODE_WAVE) View.VISIBLE else View.GONE
    }

    private fun refreshStartButton() {
        val running = WaveService.isRunning
        startStop.text = if (running) "STOP" else "START"
        startStop.backgroundTintList = ColorStateList.valueOf(
            if (running) Color.parseColor("#D32F2F") else Color.parseColor("#2E7D32")
        )
    }
}
