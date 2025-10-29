package com.lumit.warrantyactivator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

class UsageTrackerService : Service() {

    private lateinit var displayManager: DisplayManager
    private var lastScreenOnAtMs: Long? = null
    private var accumulatedActiveMs: Long = 0L
    private var activated: Boolean = false
    @Volatile private var logging: Boolean = false

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            // no-op
        }

        override fun onDisplayRemoved(displayId: Int) {
            // no-op
        }

        override fun onDisplayChanged(displayId: Int) {
            handleDisplayStateChange()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Tracking usage time"))

        accumulatedActiveMs = prefs.getLong(KEY_ACCUMULATED_MS, 0L)
        activated = prefs.getBoolean(KEY_ACTIVATED, false)

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        // Initialize state based on current display status
        if (isAnyDisplayOn()) {
            if (lastScreenOnAtMs == null) {
                lastScreenOnAtMs = System.currentTimeMillis()
            }
        }
        maybeCompleteIfThresholdReached()

		// Start periodic timer logs (every 10s) until activated
		logging = true
		thread(name = "usage-log") {
			while (logging && !activated) {
				try {
					val totalMs = getTotalActiveMs()
					val minutes = (totalMs / 60000L).toInt()
					val remaining = ((ACTIVATION_THRESHOLD_MS - totalMs).coerceAtLeast(0L) / 60000L).toInt()
					Log.d(TAG, "Timer: active=${minutes}m, remaining=${remaining}m")
					if (totalMs >= ACTIVATION_THRESHOLD_MS && !activated) {
						Log.d(TAG, "Timer threshold met: totalMs=${totalMs} >= ${ACTIVATION_THRESHOLD_MS}")
					}
					// Also trigger activation check periodically so we don't wait for a display change
					maybeCompleteIfThresholdReached()
					if (activated) {
						Log.d(TAG, "Activated flag set after threshold check")
					}
					Thread.sleep(10_000L)
				} catch (_: InterruptedException) {
					break
				}
			}
		}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        logging = false
        // Persist any in-progress session if screen is currently on
        if (lastScreenOnAtMs != null && isAnyDisplayOn() && !activated) {
            val now = System.currentTimeMillis()
            val sessionMs = now - (lastScreenOnAtMs ?: now)
            if (sessionMs > 0) {
                accumulatedActiveMs += sessionMs
                prefs.edit().putLong(KEY_ACCUMULATED_MS, accumulatedActiveMs).apply()
                lastScreenOnAtMs = now
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleDisplayStateChange() {
        if (activated) return
        val now = System.currentTimeMillis()
        if (isAnyDisplayOn()) {
            if (lastScreenOnAtMs == null) {
                lastScreenOnAtMs = now
                Log.d(TAG, "Display ON: session started at ${now}")
            }
        } else {
            if (lastScreenOnAtMs != null) {
                val sessionMs = now - (lastScreenOnAtMs ?: now)
                if (sessionMs > 0) {
                    accumulatedActiveMs += sessionMs
                    prefs.edit().putLong(KEY_ACCUMULATED_MS, accumulatedActiveMs).apply()
                    Log.d(TAG, "Display OFF: added ${(sessionMs/1000)}s, totalMs=${getTotalActiveMs()}")
                }
                lastScreenOnAtMs = null
                maybeCompleteIfThresholdReached()
            }
        }
    }

    private fun maybeCompleteIfThresholdReached() {
        if (activated) return
        val totalMs = getTotalActiveMs()
        if (totalMs >= ACTIVATION_THRESHOLD_MS) {
            activated = true
            prefs.edit().putBoolean(KEY_ACTIVATED, true).apply()
            Log.d(TAG, "Threshold reached: totalMs=${totalMs}")
            sendActivationAsync(totalMs)
        } else {
            val minutes = (totalMs / 60000L).toInt()
            val remaining = ((ACTIVATION_THRESHOLD_MS - totalMs) / 60000L).toInt()
            updateNotification("Active ${minutes}m • ${remaining}m left")
            Log.d(TAG, "Progress: active=${minutes}m, remaining=${remaining}m")
        }
    }

    private fun getTotalActiveMs(): Long {
        val now = System.currentTimeMillis()
        val currentSession = if (lastScreenOnAtMs != null && isAnyDisplayOn()) now - (lastScreenOnAtMs ?: now) else 0L
        return accumulatedActiveMs + currentSession
    }

    private fun isAnyDisplayOn(): Boolean {
        val displays = displayManager.displays
        for (d in displays) {
            if (d.state == android.view.Display.STATE_ON || d.state == android.view.Display.STATE_UNKNOWN) {
                return true
            }
        }
        return false
    }

    private fun sendActivationAsync(totalMs: Long) {
        updateNotification("Activating warranty…")
        val appContext = applicationContext
        thread(name = "activation") {
            val result = try {
                sendActivation(appContext, totalMs)
            } catch (t: Throwable) {
                false
            }
            if (result) {
                updateNotification("Warranty activated")
                Log.d(TAG, "Activation succeeded")
                stopSelf()
            } else {
                // Mark not activated to retry later
                prefs.edit().putBoolean(KEY_ACTIVATED, false).apply()
                activated = false
                updateNotification("Activation failed, will retry")
                Log.w(TAG, "Activation failed; will retry later")
            }
        }
    }

    private fun sendActivation(context: Context, totalMs: Long): Boolean {
        val endpoint = ACTIVATION_ENDPOINT
        val url = URL(endpoint)

        val brand = Build.BRAND ?: "unknown"
        val model = Build.MODEL ?: "unknown"
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val buildSerial = try { Build.getSerial() } catch (_: Throwable) { Build.SERIAL }
        val serialForPayload = if (!buildSerial.isNullOrBlank() && buildSerial != Build.UNKNOWN) buildSerial else androidId
        val activatedAt = iso8601Now()
        val activeMinutes = (totalMs / 60000L).toString()

        val boundary = "----WarrantyBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            BufferedOutputStream(connection.outputStream).use { out ->
                fun writeField(name: String, value: String) {
                    out.write((twoHyphens + boundary + lineEnd).toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"$lineEnd".toByteArray())
                    out.write("Content-Type: text/plain; charset=UTF-8$lineEnd$lineEnd".toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write(lineEnd.toByteArray())
                }

                writeField("brand", brand)
                writeField("model", model)
                writeField("serialNumber", serialForPayload ?: "unknown")
                writeField("deviceId", androidId)
                writeField("activatedAt", activatedAt)
                writeField("activeMinutes", activeMinutes)

                out.write((twoHyphens + boundary + twoHyphens + lineEnd).toByteArray())
                out.flush()
            }

            val code = connection.responseCode
            val msg = connection.responseMessage
            Log.d(TAG, "HTTP response: code=${code}, message=${msg}")
            if (code !in 200..299) {
                try {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    if (!err.isNullOrBlank()) {
                        Log.w(TAG, "HTTP error body: ${err}")
                    }
                } catch (_: Throwable) {}
            }
            return code in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private fun iso8601Now(): String {
        // Asia/Dhaka time with explicit numeric offset (e.g., 2025-10-29T18:34:56+06:00)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Dhaka")
        return sdf.format(Date())
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Warranty Activation",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Warranty Activator")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "UsageTrackerService"
        private const val PREFS_NAME = "warranty_prefs"
        private const val KEY_ACCUMULATED_MS = "accumulated_ms"
        private const val KEY_ACTIVATED = "activated"

        private const val NOTIFICATION_CHANNEL_ID = "warranty_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTIVATION_THRESHOLD_MS = 5L * 60L * 1000L // 5 minutes

        // TODO: Replace with your server endpoint
        private const val ACTIVATION_ENDPOINT = "https://warrenty-server.vercel.app/api/activate"

        fun start(context: Context) {
            val intent = Intent(context, UsageTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}


