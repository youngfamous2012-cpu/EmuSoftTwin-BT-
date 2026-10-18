package com.emusoft.twinbt.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.emusoft.twinbt.R
import com.emusoft.twinbt.bluetooth.BluetoothDeviceManager
import com.emusoft.twinbt.ui.MainActivity

class TwinBTService : LifecycleService() {

    private val tag = "TwinBT.Service"
    private val channelId = "twinbt_mirror"
    private val notifId = 1001

    inner class LocalBinder : Binder() {
        fun getService() = this@TwinBTService
    }

    private val binder = LocalBinder()
    lateinit var deviceManager: BluetoothDeviceManager
        private set

    private lateinit var engine: AudioMirrorEngine
    private var mediaProjection: MediaProjection? = null

    val isMirroring get() = engine.isRunning
    val btOneEnabled get() = engine.btOneEnabled
    val btTwoEnabled get() = engine.btTwoEnabled

    override fun onCreate() {
        super.onCreate()
        deviceManager = BluetoothDeviceManager(this)
        engine = AudioMirrorEngine(deviceManager)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(notifId, buildNotification("Ready — tap to open"))
        return START_STICKY
    }

    fun startMirroring(projectionData: Intent): Boolean {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(Activity.RESULT_OK, projectionData)

        if (!deviceManager.resolveAudioDevices()) {
            updateNotification("⚠ Could not resolve BT audio devices")
            return false
        }

        val ok = engine.start(mediaProjection!!)
        if (ok) updateNotification("🎵 Mirroring — BT One ✓  BT Two ✓")
        else updateNotification("⚠ Mirror failed to start")
        return ok
    }

    fun stopMirroring() {
        engine.stop()
        mediaProjection?.stop()
        mediaProjection = null
        updateNotification("Stopped — tap to open")
    }

    fun setSyncOffset(ms: Int) { engine.syncOffsetMs = ms }

    /** Toggle BT One on/off. Returns the new enabled state. */
    fun toggleBtOne(): Boolean {
        engine.btOneEnabled = !engine.btOneEnabled
        refreshNotification()
        return engine.btOneEnabled
    }

    /** Toggle BT Two on/off. Returns the new enabled state. */
    fun toggleBtTwo(): Boolean {
        engine.btTwoEnabled = !engine.btTwoEnabled
        refreshNotification()
        return engine.btTwoEnabled
    }

    private fun refreshNotification() {
        val oneLabel = if (engine.btOneEnabled) "BT One ✓" else "BT One ✗"
        val twoLabel = if (engine.btTwoEnabled) "BT Two ✓" else "BT Two ✗"
        updateNotification("🎵 Mirroring — $oneLabel  $twoLabel")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Twin BT Mirror", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Audio mirror status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_bt_notify)
            .setContentTitle("EmuSoft Twin BT")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(notifId, buildNotification(text))

    override fun onDestroy() {
        engine.stop(); mediaProjection?.stop(); super.onDestroy()
    }
}
