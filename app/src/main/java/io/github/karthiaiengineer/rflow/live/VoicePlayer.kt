package io.github.karthiaiengineer.rflow.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.karthiaiengineer.rflow.core.Pcm
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Speak the translation: Gemini's own voice (24 kHz PCM16) played as it streams, as on the desktop
 * (`sst/live/speaker.py`). The pieces come every ~250 ms and now and then late, so each time the voice starts its first
 * piece waits [CUSHION] and what follows plays that far behind, without holes. Other apps are lowered while it speaks
 * (audio focus, "may duck"). Its usage is the assistant's, which playback capture never hears: it can't translate itself.
 * [speakingUntil] tells the microphone when the room may still hear the voice.
 */
class VoicePlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).build()
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    @Volatile var speakingUntil = 0L  // System.currentTimeMillis() until which the voice (and its echo) may be heard
        private set
    private val track = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Pcm.VOICE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(Pcm.VOICE_RATE * 2)  // 1 s
        .build()
    private val thread = Thread(::play, "voice").apply { isDaemon = true; start() }

    val speaking get() = System.currentTimeMillis() < speakingUntil

    /** A piece of the voice. Pieces of pure pause are left out while the voice is quiet (they'd only lengthen silence). */
    fun hear(pcm: ByteArray, rate: Int) {
        if (rate != Pcm.VOICE_RATE) return  // Google says "always 24 kHz"
        if (!speaking && Pcm.level(pcm) < PAUSE_LEVEL) return
        queue.put(pcm)
        val now = System.currentTimeMillis()
        playsUntil = maxOf(playsUntil, now + CUSHION_MS) + (pcm.size / (2.0 * rate) * 1000).toLong()
        speakingUntil = playsUntil + TAIL_MS  // the room may still hear its last word a moment later
    }

    private var playsUntil = 0L

    private fun play() {
        var focused = false
        var idleSince = 0L
        while (running) {
            val first = queue.poll(200, TimeUnit.MILLISECONDS)
            if (first == null) {
                if (focused && System.currentTimeMillis() - idleSince > 1000) {
                    audio.abandonAudioFocusRequest(focus)
                    focused = false
                }
                continue
            }
            if (!focused) {
                audio.requestAudioFocus(focus)
                focused = true
            }
            Thread.sleep(CUSHION_MS)  // the cushion: later pieces may come this late without a hole
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            var piece: ByteArray? = first
            while (piece != null && running) {
                track.write(piece, 0, piece.size)
                piece = queue.poll(STALL_MS, TimeUnit.MILLISECONDS)
            }
            idleSince = System.currentTimeMillis()
        }
        audio.abandonAudioFocusRequest(focus)
    }

    fun close() {
        running = false
        queue.clear()
        thread.interrupt()
        runCatching { track.pause(); track.flush(); track.release() }
    }

    private companion object {
        const val CUSHION_MS = 500L
        const val STALL_MS = 600L  // longer than a late piece: the voice has paused
        const val TAIL_MS = 400L
        val PAUSE_LEVEL = Math.pow(10.0, -45.0 / 20)
    }
}
