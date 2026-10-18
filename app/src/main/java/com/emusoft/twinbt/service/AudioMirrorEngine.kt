package com.emusoft.twinbt.service

import android.media.*
import android.media.projection.MediaProjection
import android.util.Log
import com.emusoft.twinbt.bluetooth.BluetoothDeviceManager
import kotlinx.coroutines.*

/**
 * Core audio engine for EmuSoft Twin BT.
 *
 * Captures system audio via AudioPlaybackCapture and writes PCM to two
 * AudioTrack instances, each pinned to one Bluetooth speaker.
 *
 * [btOneEnabled] and [btTwoEnabled] can be toggled at any time while
 * the engine is running to independently mute/unmute each speaker
 * without stopping the capture session.
 */
class AudioMirrorEngine(
    private val deviceManager: BluetoothDeviceManager
) {
    private val tag = "TwinBT.Engine"

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val outChannelConfig = AudioFormat.CHANNEL_OUT_STEREO

    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        .coerceAtLeast(8192)

    private var audioRecord: AudioRecord? = null
    private var trackOne: AudioTrack? = null
    private var trackTwo: AudioTrack? = null

    private var mirrorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Delay in ms applied to BT One to align with BT Two (default 0). */
    var syncOffsetMs: Int = 0

    /** Toggle BT One output on/off without stopping the mirror session. */
    @Volatile var btOneEnabled: Boolean = true

    /** Toggle BT Two output on/off without stopping the mirror session. */
    @Volatile var btTwoEnabled: Boolean = true

    val isRunning: Boolean get() = mirrorJob?.isActive == true

    fun start(mediaProjection: MediaProjection): Boolean {
        if (isRunning) return false

        val devOne = deviceManager.btOne.audioDevice
        val devTwo = deviceManager.btTwo.audioDevice

        if (devOne == null || devTwo == null) {
            Log.e(tag, "Audio devices not resolved — cannot start")
            return false
        }

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(tag, "AudioRecord failed to initialize")
            return false
        }

        trackOne = buildTrack(devOne)
        trackTwo = buildTrack(devTwo)

        if (trackOne == null || trackTwo == null) {
            release(); return false
        }

        // Reset toggles to enabled whenever a fresh session starts
        btOneEnabled = true
        btTwoEnabled = true

        audioRecord!!.startRecording()
        trackOne!!.play()
        trackTwo!!.play()

        mirrorJob = scope.launch { mirrorLoop() }
        Log.i(tag, "Mirror started — BT One: ${devOne.productName}, BT Two: ${devTwo.productName}")
        return true
    }

    private fun buildTrack(device: AudioDeviceInfo): AudioTrack? {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val fmt = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(outChannelConfig)
            .setEncoding(encoding)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        return if (track.setPreferredDevice(device)) track
        else { track.release(); null }
    }

    private val silence by lazy { ByteArray(bufferSize) }

    private suspend fun mirrorLoop() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(bufferSize)
        val bytesPerMs = (sampleRate * 2 * 2) / 1000
        val delayBuffer = ArrayDeque<ByteArray>()
        var delayBytesAccum = 0

        while (isActive) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue

            val chunk = buffer.copyOf(read)

            if (syncOffsetMs <= 0) {
                // Write live PCM or silence depending on each speaker's toggle
                if (btOneEnabled) trackOne?.write(chunk, 0, read)
                else trackOne?.write(silence, 0, read)

                if (btTwoEnabled) trackTwo?.write(chunk, 0, read)
                else trackTwo?.write(silence, 0, read)
            } else {
                // BT Two is the reference (no delay); BT One is delayed
                if (btTwoEnabled) trackTwo?.write(chunk, 0, read)
                else trackTwo?.write(silence, 0, read)

                delayBuffer.addLast(chunk)
                delayBytesAccum += read

                val targetBytes = syncOffsetMs * bytesPerMs
                while (delayBytesAccum > targetBytes && delayBuffer.isNotEmpty()) {
                    val delayed = delayBuffer.removeFirst()
                    if (btOneEnabled) trackOne?.write(delayed, 0, delayed.size)
                    else trackOne?.write(silence, 0, delayed.size)
                    delayBytesAccum -= delayed.size
                }
            }
        }
        Log.i(tag, "Mirror loop ended")
    }

    fun stop() {
        mirrorJob?.cancel()
        mirrorJob = null
        release()
    }

    private fun release() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { trackOne?.stop(); trackOne?.release() } catch (_: Exception) {}
        try { trackTwo?.stop(); trackTwo?.release() } catch (_: Exception) {}
        audioRecord = null; trackOne = null; trackTwo = null
    }
}
