package com.wifiaviso

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var networks: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun text(t: String, size: Float = 16f) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, pad, 0, 0)
        }

        root.addView(text("Wifi Aviso", 26f))
        root.addView(
            text(
                "Te avisa con una notificación cuando la señal del Wi-Fi está baja y hay otro " +
                    "router más fuerte cerca. Al tocarla se abren los ajustes de Wi-Fi.",
                14f
            )
        )

        // Umbral de señal baja: -90 .. -50 dBm
        val thLabel = text("")
        val th = SeekBar(this).apply {
            max = 40
            progress = prefs.getInt("threshold", -70) + 90
        }
        fun thText() { thLabel.text = "Avisar cuando la señal sea peor que: ${th.progress - 90} dBm" }
        thText()
        th.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("threshold", p - 90).apply(); thText()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(thLabel)
        root.addView(th)
        root.addView(text("-50 = muy fuerte · -70 = 2 barras · -80 = casi sin señal", 12f))

        // Diferencia mínima: 5 .. 25 dB
        val mgLabel = text("")
        val mg = SeekBar(this).apply {
            max = 20
            progress = prefs.getInt("margin", 8) - 5
        }
        fun mgText() { mgLabel.text = "El otro router debe ser más fuerte por: ${mg.progress + 5} dB" }
        mgText()
        mg.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("margin", p + 5).apply(); mgText()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(mgLabel)
        root.addView(mg)

        root.addView(text("Nombres de tus redes Wi-Fi (separados por coma). Vacío = solo la red actual:"))
        networks = EditText(this).apply {
            setText(prefs.getString("networks", ""))
            hint = "Ej: CasaPiso1, CasaPiso2"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(networks)

        toggle = Button(this).apply {
            setOnClickListener { if (prefs.getBoolean("running", false)) stop() else start() }
        }
        root.addView(
            toggle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad * 2 }
        )

        status = text("", 14f)
        root.addView(status)
        root.addView(
            text(
                "Importante: deja activada la ubicación del teléfono (Android la exige para leer " +
                    "las redes cercanas) y quita la optimización de batería para esta app.",
                12f
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        prefs.edit().putString("networks", networks.text.toString()).apply()
        super.onPause()
    }

    private fun refresh() {
        val running = prefs.getBoolean("running", false)
        toggle.text = if (running) "Detener vigilancia" else "Iniciar vigilancia"
        status.text = if (running) "Vigilancia activa." else "Vigilancia detenida."
    }

    private fun start() {
        prefs.edit().putString("networks", networks.text.toString()).apply()

        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, WifiMonitorService::class.java))
        prefs.edit().putBoolean("running", true).apply()
        refresh()
    }

    private fun stop() {
        stopService(Intent(this, WifiMonitorService::class.java))
        prefs.edit().putBoolean("running", false).apply()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            start()
        } else {
            status.text = "Faltan permisos (ubicación y notificaciones). Actívalos en Ajustes > Apps > Wifi Aviso."
        }
    }
}
