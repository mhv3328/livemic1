package com.example.livemic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.Process
import kotlin.math.max

/**
 * Mic -> speaker loopback tuned for the lowest delay the phone allows:
 *  - native sample rate + native burst size (avoids resampling / extra buffering)
 *  - AudioTrack in PERFORMANCE_MODE_LOW_LATENCY, buffer trimmed to 2 bursts
 *  - VOICE_PERFORMANCE mic source (Android 10+, made for live performance)
 *  - echo canceller / auto gain turned off (they add delay and fight the loopback)
 *  - optional Bluetooth "call mode" (SCO): much lower Bluetooth delay, lower quality
 */
class LoopbackEngine(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var gain = 1.0f
    @Volatile private var running = false
    val isRunning get() = running

    private var worker: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val effects = mutableListOf<AudioEffect>()
    private var scoActive = false

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked in MainActivity
    fun start(scoMode: Boolean): String {
        if (running) return "Already running"

        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        val nativeRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

        var sampleRate = nativeRate
        var note = ""
        if (scoMode) {
            scoActive = enableSco()
            if (scoActive) sampleRate = 16000
            else note = "\n(Speaker doesn't support call mode – using normal mode.)"
        }

        val enc = AudioFormat.ENCODING_PCM_16BIT
        val inMask = AudioFormat.CHANNEL_IN_MONO
        val outMask = AudioFormat.CHANNEL_OUT_MONO

        // ---- Microphone ----
        val source = when {
            scoActive -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            Build.VERSION.SDK_INT >= 29 -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
            else -> MediaRecorder.AudioSource.MIC
        }
        val minIn = AudioRecord.getMinBufferSize(sampleRate, inMask, enc)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(inMask).setEncoding(enc).build()
            )
            .setBufferSizeInBytes(max(minIn, burst * 2 * 2))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); disableSco()
            throw IllegalStateException("Microphone is busy or unavailable")
        }
        // Always use the phone's own mic, even when a Bluetooth device is connected
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { rec.preferredDevice = it }
        disableProcessing(rec.audioSessionId)

        // ---- Speaker ----
        val usage = if (scoActive) AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else AudioAttributes.USAGE_MEDIA
        val minOut = AudioTrack.getMinBufferSize(sampleRate, outMask, enc)
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(outMask).setEncoding(enc).build()
            )
            .setBufferSizeInBytes(minOut)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val outFrames = trk.setBufferSizeInFrames(burst * 2) // trim output queue

        record = rec
        track = trk
        running = true

        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(burst)
            trk.play()
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val g = gain
                if (g != 1f) {
                    for (i in 0 until n) {
                        buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
                trk.write(buf, 0, n)
            }
        }, "LiveMicLoop").also { it.start() }

        val mode = if (scoActive) "Bluetooth call mode" else "Normal mode"
        val bufMs = (max(outFrames, burst) + burst) * 1000 / sampleRate
        return "LIVE – $mode, ${sampleRate / 1000} kHz, app delay ≈ $bufMs ms$note\n" +
               "If it squeals, move the phone away from the speaker."
    }

    fun stop() {
        if (!running) return
        running = false
        worker?.join(500)
        worker = null
        record?.run { try { stop() } catch (_: Exception) {}; release() }
        record = null
        track?.run { try { pause(); flush(); stop() } catch (_: Exception) {}; release() }
        track = null
        effects.forEach { it.release() }
        effects.clear()
        disableSco()
    }

    private fun disableProcessing(session: Int) {
        if (AcousticEchoCanceler.isAvailable())
            AcousticEchoCanceler.create(session)?.let { it.enabled = false; effects += it }
        if (AutomaticGainControl.isAvailable())
            AutomaticGainControl.create(session)?.let { it.enabled = false; effects += it }
    }

    @Suppress("DEPRECATION")
    private fun enableSco(): Boolean {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            dev != null && am.setCommunicationDevice(dev)
        } else {
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            true
        }
        if (!ok) am.mode = AudioManager.MODE_NORMAL
        return ok
    }

    @Suppress("DEPRECATION")
    private fun disableSco() {
        if (!scoActive) return
        if (Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        } else {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
        }
        am.mode = AudioManager.MODE_NORMAL
        scoActive = false
    }
}
