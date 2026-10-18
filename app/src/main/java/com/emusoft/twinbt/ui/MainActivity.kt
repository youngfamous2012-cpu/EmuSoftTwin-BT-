package com.emusoft.twinbt.ui

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.emusoft.twinbt.R
import com.emusoft.twinbt.databinding.ActivityMainBinding
import com.emusoft.twinbt.service.TwinBTService

class MainActivity : AppCompatActivity() {

    private val tag = "TwinBT.UI"
    private lateinit var binding: ActivityMainBinding

    private var service: TwinBTService? = null
    private var bound = false

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TwinBTService.LocalBinder).getService()
            bound = true
            refreshUi()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false; service = null
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startServiceAndBind()
        else toast("Permissions required for Twin BT to work")
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val ok = service?.startMirroring(result.data!!) ?: false
            if (ok) {
                binding.btnMirror.text = getString(R.string.stop_mirroring)
                binding.btnMirror.backgroundTintList =
                    getColorStateList(R.color.stop_red)
                binding.statusText.text = getString(R.string.status_mirroring)
                binding.statusText.setTextColor(getColor(R.color.green_active))
                enableSpeakerToggles(true)
            } else {
                toast("Failed to start mirror — are both speakers connected?")
            }
        } else {
            toast("Screen capture permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClickListeners()
        setupSyncSlider()
        if (hasPermissions()) startServiceAndBind() else requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bindService(Intent(this, TwinBTService::class.java), serviceConn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) { unbindService(serviceConn); bound = false }
    }

    private fun setupClickListeners() {
        binding.btnPickBtOne.setOnClickListener { pickSpeaker(1) }
        binding.btnPickBtTwo.setOnClickListener { pickSpeaker(2) }

        // Speaker toggles — only active while mirroring
        binding.switchBtOne.setOnCheckedChangeListener { _, _ ->
            if (service?.isMirroring == true) {
                val enabled = service!!.toggleBtOne()
                updateSpeakerCardAlpha(isOne = true, enabled = enabled)
            }
        }
        binding.switchBtTwo.setOnCheckedChangeListener { _, _ ->
            if (service?.isMirroring == true) {
                val enabled = service!!.toggleBtTwo()
                updateSpeakerCardAlpha(isOne = false, enabled = enabled)
            }
        }

        binding.btnMirror.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.isMirroring) {
                svc.stopMirroring()
                binding.btnMirror.text = getString(R.string.start_mirroring)
                binding.btnMirror.backgroundTintList = getColorStateList(R.color.accent_blue)
                binding.statusText.text = getString(R.string.status_ready)
                binding.statusText.setTextColor(getColor(R.color.text_secondary))
                enableSpeakerToggles(false)
                // Reset toggles visually
                binding.switchBtOne.isChecked = true
                binding.switchBtTwo.isChecked = true
                updateSpeakerCardAlpha(isOne = true, enabled = true)
                updateSpeakerCardAlpha(isOne = false, enabled = true)
            } else {
                if (!svc.deviceManager.bothAssigned()) {
                    toast("Please assign both BT One and BT Two first"); return@setOnClickListener
                }
                if (!svc.deviceManager.bothConnected()) {
                    toast("Both speakers must be connected via Bluetooth"); return@setOnClickListener
                }
                requestAudioCapture()
            }
        }
    }

    private fun setupSyncSlider() {
        binding.syncSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val ms = value.toInt()
                binding.syncLabel.text = getString(R.string.sync_label, ms)
                service?.setSyncOffset(ms)
            }
        }
    }

    /** Enable/disable the speaker toggle switches (only meaningful while mirroring). */
    private fun enableSpeakerToggles(enabled: Boolean) {
        binding.switchBtOne.isEnabled = enabled
        binding.switchBtTwo.isEnabled = enabled
    }

    /** Dim the speaker card when its output is muted. */
    private fun updateSpeakerCardAlpha(isOne: Boolean, enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.45f
        if (isOne) binding.switchBtOne.alpha = alpha
        else binding.switchBtTwo.alpha = alpha
    }

    private fun pickSpeaker(slot: Int) {
        val svc = service ?: return
        val paired = svc.deviceManager.getPairedDevices()
        if (paired.isEmpty()) {
            toast("No paired Bluetooth devices found — pair your speakers first"); return
        }
        val names = paired.map { "${it.name} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (slot == 1) "Assign BT One" else "Assign BT Two")
            .setItems(names) { _, idx ->
                val device = paired[idx]
                val dm = svc.deviceManager
                if (slot == 1) {
                    dm.assign(dm.btOne, device)
                    binding.btnPickBtOne.text = device.name
                    binding.btOneStatus.text = getString(R.string.device_assigned, device.name)
                } else {
                    dm.assign(dm.btTwo, device)
                    binding.btnPickBtTwo.text = device.name
                    binding.btTwoStatus.text = getString(R.string.device_assigned, device.name)
                }
                updateMirrorButton()
            }.show()
    }

    private fun requestAudioCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun hasPermissions(): Boolean {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(needed.toTypedArray())
    }

    private fun startServiceAndBind() {
        val intent = Intent(this, TwinBTService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)
    }

    private fun updateMirrorButton() {
        binding.btnMirror.isEnabled = service?.deviceManager?.bothAssigned() == true
    }

    private fun refreshUi() {
        val svc = service ?: return
        val dm = svc.deviceManager
        dm.btOne.device?.let {
            binding.btnPickBtOne.text = it.name
            binding.btOneStatus.text = getString(R.string.device_assigned, it.name)
        }
        dm.btTwo.device?.let {
            binding.btnPickBtTwo.text = it.name
            binding.btTwoStatus.text = getString(R.string.device_assigned, it.name)
        }
        if (svc.isMirroring) {
            binding.btnMirror.text = getString(R.string.stop_mirroring)
            binding.btnMirror.backgroundTintList = getColorStateList(R.color.stop_red)
            binding.statusText.text = getString(R.string.status_mirroring)
            binding.statusText.setTextColor(getColor(R.color.green_active))
            enableSpeakerToggles(true)
            binding.switchBtOne.isChecked = svc.btOneEnabled
            binding.switchBtTwo.isChecked = svc.btTwoEnabled
        }
        updateMirrorButton()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
