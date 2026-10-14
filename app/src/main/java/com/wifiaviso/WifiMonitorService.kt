package com.wifiaviso

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Vigila la señal del Wi-Fi actual. Cuando baja del umbral y hay otro router
 * claramente más fuerte, muestra una notificación emergente. Al pulsarla se
 * abren los ajustes de Wi-Fi para cambiar de red.
 */
class WifiMonitorService : Service() {

    companion object {
        const val CH_STATUS = "estado"
        const val CH_ALERT = "alerta"
        const val ID_STATUS = 1
        const val ID_ALERT = 2

        private const val TICK_MS = 5_000L
        private const val SCAN_WEAK_MS = 30_000L   // escanea más seguido si la señal es débil
        private const val SCAN_NORMAL_MS = 90_000L // Android limita a ~4 escaneos cada 2 min
        private const val ALERT_COOLDOWN_MS = 120_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifi: WifiManager
    private lateinit var nm: NotificationManager
    private lateinit var prefs: SharedPreferences

    private var lastScan = -1L
    private var lastAlert = -1L

    private val tick = object : Runnable {
        override fun run() {
            try {
                check()
            } catch (_: SecurityException) {
                updateStatus("Falta el permiso de ubicación")
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        nm = getSystemService(NotificationManager::class.java)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Vigilancia de señal", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Aviso de señal débil", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )

        ServiceCompat.startForeground(
            this,
            ID_STATUS,
            statusNotification("Vigilando la señal Wi-Fi…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        nm.cancel(ID_ALERT)
        prefs.edit().putBoolean("running", false).apply()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun check() {
        if (!wifi.isWifiEnabled) {
            updateStatus("Wi-Fi apagado")
            clearAlert()
            return
        }
        val info: WifiInfo? = wifi.connectionInfo
        if (info == null || info.supplicantState != SupplicantState.COMPLETED || info.rssi <= -127) {
            updateStatus("Sin conexión Wi-Fi")
            clearAlert()
            return
        }

        val rssi = info.rssi
        val threshold = prefs.getInt("threshold", -70)
        val margin = prefs.getInt("margin", 8)
        val now = SystemClock.elapsedRealtime()
        val weak = rssi <= threshold

        val scanEvery = if (weak) SCAN_WEAK_MS else SCAN_NORMAL_MS
        if (lastScan < 0 || now - lastScan >= scanEvery) {
            lastScan = now
            wifi.startScan()
        }

        val bars = WifiManager.calculateSignalLevel(rssi, 5)
        updateStatus("Señal actual: $rssi dBm ($bars/4)")

        if (!weak) {
            if (rssi >= threshold + 5) clearAlert() // histéresis para no parpadear
            return
        }
        if (lastAlert >= 0 && now - lastAlert < ALERT_COOLDOWN_MS) return

        val best = bestCandidate(info, rssi, margin) ?: return
        lastAlert = now
        showAlert(info, rssi, best)
    }

    @Suppress("DEPRECATION")
    private fun bestCandidate(info: WifiInfo, rssi: Int, margin: Int): ScanResult? {
        val currentSsid = info.ssid?.trim('"') ?: ""
        val mine = (prefs.getString("networks", "") ?: "")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val allowed = (mine + currentSsid.lowercase()).toSet()
        val currentBssid = info.bssid?.lowercase()

        return wifi.scanResults
            .filter { it.SSID.isNotEmpty() && it.SSID.lowercase() in allowed }
            .filter { it.BSSID.lowercase() != currentBssid }
            .filter { it.level >= rssi + margin }
            .maxByOrNull { it.level }
    }

    @Suppress("DEPRECATION")
    private fun showAlert(info: WifiInfo, rssi: Int, best: ScanResult) {
        val currentSsid = info.ssid?.trim('"') ?: ""
        val text = if (best.SSID == currentSsid) {
            "Estás a $rssi dBm. Hay otro router de «${best.SSID}» más cerca (${best.level} dBm). " +
                "Toca, apaga y enciende el Wi-Fi para reconectar al más fuerte."
        } else {
            "Estás a $rssi dBm. Toca para cambiar a «${best.SSID}» (${best.level} dBm)."
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("Señal Wi-Fi débil")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(ID_ALERT, n)
    }

    private fun clearAlert() {
        nm.cancel(ID_ALERT)
        lastAlert = -1L
    }

    private fun updateStatus(text: String) {
        nm.notify(ID_STATUS, statusNotification(text))
    }

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("Wifi Aviso activo")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
