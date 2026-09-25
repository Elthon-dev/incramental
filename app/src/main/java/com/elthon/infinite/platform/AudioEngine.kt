package com.elthon.infinite.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

enum class Sfx { TAP, HIT, CRIT, KILL, HURT, ABILITY, CARD, SYNERGY, STAGE, DEFEAT }

class AudioEngine(context: Context) {
    private val tracks = HashMap<Sfx, AudioTrack>()
    private var ambient: AudioTrack? = null
    @Volatile var soundEnabled: Boolean = true
    @Volatile var musicEnabled: Boolean = true

    init {
        context.applicationContext
        buildTracks()
        buildAmbient()
    }

    fun play(sound: Sfx, volume: Float = 1f) {
        if (!soundEnabled) return
        val track = tracks[sound] ?: return
        try {
            val level = volume.coerceIn(0.05f, 1f)
            track.setVolume(level)
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        } catch (error: Exception) {
            Log.w(TAG, "playback failed for $sound", error)
        }
    }

    fun startAmbient() {
        val track = ambient ?: return
        if (!musicEnabled) return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.setVolume(0.16f)
                track.play()
            }
        } catch (error: Exception) {
            Log.w(TAG, "ambient failed", error)
        }
    }

    fun stopAmbient() {
        try {
            ambient?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.pause()
        } catch (error: Exception) {
            Log.w(TAG, "ambient pause failed", error)
        }
    }

    fun release() {
        tracks.values.forEach { track ->
            try {
                track.stop()
                track.release()
            } catch (error: Exception) {
                Log.w(TAG, "release failed", error)
            }
        }
        tracks.clear()
        ambient?.let { track ->
            try {
                track.stop()
                track.release()
            } catch (error: Exception) {
                Log.w(TAG, "ambient release failed", error)
            }
        }
        ambient = null
    }

    private fun buildTracks() {
        Sfx.entries.forEach { sound ->
            val buffer = synth(sound.durationSeconds()) { time, envelope -> sound.sample(time, envelope) }
            tracks[sound] = createTrack(buffer) ?: return@forEach
        }
    }

    private fun buildAmbient() {
        val frames = (SAMPLE_RATE * 4).toInt()
        val buffer = ShortArray(frames)
        var index = 0
        while (index < frames) {
            val time = index.toDouble() / SAMPLE_RATE
            val envelope = 0.5 - 0.5 * sin(2.0 * PI * time / 4.0)
            var value = 0.0
            value += sin(2.0 * PI * 55.0 * time) * 0.35
            value += sin(2.0 * PI * 82.5 * time) * 0.22
            value += sin(2.0 * PI * 110.0 * time + sin(time * 0.7)) * 0.14
            value += sin(2.0 * PI * 164.8 * time) * 0.07 * envelope
            buffer[index] = (value.coerceIn(-1.0, 1.0) * 9_000.0).toInt().toShort()
            index++
        }
        ambient = createTrack(buffer, loop = true)
        if (ambient != null) ambient?.setVolume(0f)
    }

    private fun createTrack(buffer: ShortArray, loop: Boolean = false): AudioTrack? = try {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(buffer.size * 2, minBuffer)
        val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                size,
                AudioTrack.MODE_STATIC
            )
        }
        track.write(buffer, 0, buffer.size)
        if (loop) track.setLoopPoints(0, buffer.size, -1)
        track
    } catch (error: Exception) {
        Log.w(TAG, "track creation failed", error)
        null
    }

    private fun synth(duration: Double, generator: (Double, Double) -> Double): ShortArray {
        val frames = maxOf(1, (SAMPLE_RATE * duration).toInt())
        val buffer = ShortArray(frames)
        var index = 0
        while (index < frames) {
            val time = index.toDouble() / SAMPLE_RATE
            val envelope = exp(-4.2 * time / duration)
            val value = generator(time, envelope).coerceIn(-1.0, 1.0)
            buffer[index] = (value * 26_000.0).toInt().toShort()
            index++
        }
        return buffer
    }

    private fun Sfx.durationSeconds(): Double = when (this) {
        Sfx.TAP -> 0.05
        Sfx.HIT -> 0.09
        Sfx.CRIT -> 0.16
        Sfx.KILL -> 0.22
        Sfx.HURT -> 0.20
        Sfx.ABILITY -> 0.26
        Sfx.CARD -> 0.18
        Sfx.SYNERGY -> 0.34
        Sfx.STAGE -> 0.30
        Sfx.DEFEAT -> 0.55
    }

    private fun Sfx.sample(time: Double, envelope: Double): Double = when (this) {
        Sfx.TAP -> tone(time, 620.0, envelope)
        Sfx.HIT -> noise(time, envelope) * 0.5 + tone(time, 320.0, envelope) * 0.5
        Sfx.CRIT -> tone(time, 880.0, envelope) * 0.6 + tone(time, 1320.0, envelope) * 0.4
        Sfx.KILL -> tone(time, 240.0, envelope * (1.0 - 0.4 * time)) + noise(time, envelope) * 0.3
        Sfx.HURT -> tone(time, 150.0, envelope) * 0.8 + noise(time, envelope) * 0.4
        Sfx.ABILITY -> tone(time, 420.0, envelope) + tone(time, 630.0, envelope * 0.6)
        Sfx.CARD -> tone(time, 740.0, envelope) + tone(time, 1110.0, envelope * 0.5)
        Sfx.SYNERGY -> tone(time, 520.0, envelope) + tone(time, 780.0, envelope * 0.7) + tone(time, 1040.0, envelope * 0.4)
        Sfx.STAGE -> tone(time, 660.0, envelope) + tone(time, 990.0, envelope * 0.6)
        Sfx.DEFEAT -> tone(time, 200.0, envelope) * 0.7 + tone(time, 96.0, envelope) * 0.6
    }

    private fun tone(time: Double, frequency: Double, envelope: Double): Double =
        sin(2.0 * PI * frequency * time) * envelope

    private fun noise(time: Double, envelope: Double): Double {
        val hashed = ((time * 31_337.0).toInt() * 1_103_515_245 + 12_345) and 0x7fffffff
        return (hashed / 1_073_741_823.5 - 1.0) * envelope * abs(sin(time * 90.0))
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
        const val TAG = "InfiniteAudio"
    }
}
