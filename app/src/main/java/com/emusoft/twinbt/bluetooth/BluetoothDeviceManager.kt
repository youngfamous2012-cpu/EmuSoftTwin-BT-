package com.emusoft.twinbt.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Manages discovery and state of BT One and BT Two speakers.
 * Wraps the system BluetoothAdapter and AudioManager to expose
 * only what TwinBT needs.
 */
class BluetoothDeviceManager(private val context: Context) {

    private val tag = "TwinBT.BTDevMgr"

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    data class SpeakerSlot(
        val label: String,               // "BT One" or "BT Two"
        var device: BluetoothDevice? = null,
        var audioDevice: AudioDeviceInfo? = null
    )

    val btOne = SpeakerSlot("BT One")
    val btTwo = SpeakerSlot("BT Two")

    /** Returns all currently paired A2DP-capable Bluetooth devices. */
    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = adapter ?: return emptyList()
        return adapter.bondedDevices
            ?.filter { it.bluetoothClass?.hasService(android.bluetooth.BluetoothClass.Service.AUDIO) == true }
            ?: emptyList()
    }

    /**
     * Resolves AudioDeviceInfo for assigned Bluetooth devices.
     * Must be called AFTER both speakers are connected so the
     * AudioManager can see them as active output devices.
     */
    fun resolveAudioDevices(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        Log.d(tag, "Available output devices: ${outputs.map { it.productName }}")

        fun findAudioDevice(slot: SpeakerSlot): AudioDeviceInfo? {
            val mac = slot.device?.address ?: return null
            return outputs.firstOrNull { info ->
                info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                    info.address.equals(mac, ignoreCase = true)
            }
        }

        btOne.audioDevice = findAudioDevice(btOne)
        btTwo.audioDevice = findAudioDevice(btTwo)

        Log.d(tag, "BT One audio device: ${btOne.audioDevice?.productName}")
        Log.d(tag, "BT Two audio device: ${btTwo.audioDevice?.productName}")

        return btOne.audioDevice != null && btTwo.audioDevice != null
    }

    /** True if both slots have a BT device assigned. */
    fun bothAssigned() = btOne.device != null && btTwo.device != null

    /** True if both BT devices are currently connected via A2DP. */
    fun bothConnected(): Boolean {
        val a2dp = bluetoothManager.getConnectedDevices(BluetoothProfile.A2DP)
        val oneOk = btOne.device?.let { a2dp.contains(it) } ?: false
        val twoOk = btTwo.device?.let { a2dp.contains(it) } ?: false
        return oneOk && twoOk
    }

    fun assign(slot: SpeakerSlot, device: BluetoothDevice) {
        slot.device = device
        slot.audioDevice = null   // will be resolved when mirroring starts
    }

    fun clear() {
        btOne.device = null; btOne.audioDevice = null
        btTwo.device = null; btTwo.audioDevice = null
    }
}
