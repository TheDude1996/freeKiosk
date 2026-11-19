package com.kioskbrowser.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

/** Microphone level monitor ("acoustic detection"). Emits a callback when the threshold is exceeded. */
class SoundDetector(private val thresholdDb: Int, private val onLoud: (Double) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            val rate = 16000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(KioskApp.TAG, "AudioRecord init failed"); running = false; return@Thread
            }
            val buf = ShortArray(size / 2)
            var lastTrigger = 0L
            rec.startRecording()
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                    val rms = sqrt(sum / n)
                    // Approximate SPL: 0 dBFS ≈ 90 dB
                    val db = if (rms > 0) 20 * log10(rms / 32767.0) + 90 else 0.0
                    KioskState.lastSoundLevelDb = Math.round(db * 10) / 10.0
                    val now = System.currentTimeMillis()
                    if (db >= thresholdDb && now - lastTrigger > 2000) {
                        lastTrigger = now
                        KioskState.lastSoundTime = now
                        onLoud(db)
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "sound-detector").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread = null
    }
}
