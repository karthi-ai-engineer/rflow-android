package io.github.karthiaiengineer.rflow.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import io.github.karthiaiengineer.rflow.MainActivity
import io.github.karthiaiengineer.rflow.R
import io.github.karthiaiengineer.rflow.RflowApp
import io.github.karthiaiengineer.rflow.ai.LiveEvent
import io.github.karthiaiengineer.rflow.ai.LiveTranslate
import io.github.karthiaiengineer.rflow.core.Languages
import io.github.karthiaiengineer.rflow.core.Pcm
import io.github.karthiaiengineer.rflow.core.WavWriter
import io.github.karthiaiengineer.rflow.meeting.MeetingStore
import java.io.File

/**
 * A live session in the foreground, as Android requires for a microphone in use: live translation (the room or what
 * the phone plays), Meeting mode (the room, recorded, translated live) or Conversation (two languages, one microphone).
 * A notification shows while it runs, with Stop. The audio goes to Gemini Live Translate in 100 ms frames; Meeting
 * mode also writes it to the meeting's room.wav. With Speak the translation, the microphone's frames are silenced while
 * the voice plays (and a moment after), so Gemini never translates its own voice back.
 */
class LiveService : Service() {
    private var engines: List<Pair<Int, LiveTranslate>> = emptyList()
    private var record: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var player: VoicePlayer? = null
    private var wav: WavWriter? = null
    private var overlay: CaptionOverlay? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null
    private var spec: SessionSpec? = null
    private var meetingDir: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stop()
            START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (spec != null) return  // one session at a time
        val spec = SessionSpec(
            mode = Mode.valueOf(intent.getStringExtra("mode")!!),
            source = Source.valueOf(intent.getStringExtra("source") ?: Source.ROOM.name),
            target = intent.getStringExtra("target") ?: "en",
            theirs = intent.getStringExtra("theirs") ?: "ja",
            speak = intent.getBooleanExtra("speak", false),
            saver = intent.getBooleanExtra("saver", true),
        )
        this.spec = spec
        val phone = spec.source == Source.PHONE
        val type = if (phone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTE_ID, notification(spec), type)

        val app = application as RflowApp
        val key = app.vault.read()
        val dir = if (spec.mode == Mode.MEETING) MeetingStore.create(this, spec.target) else null
        meetingDir = dir
        LiveHub.reset(spec, dir?.name.orEmpty())
        if (key == null) return fail("Add your Gemini key in Settings first.")

        try {
            record = if (phone) playbackCapture(intent) else microphone()
        } catch (e: Exception) {
            return fail(e.message ?: "The audio couldn't be opened.")
        }
        if (dir != null) {
            wav = WavWriter(File(dir, MeetingStore.ROOM))
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rflow:meeting").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        if (spec.speak) player = VoicePlayer(this)

        val lanes = if (spec.mode == Mode.CONVERSATION) listOf(0 to spec.target, 1 to spec.theirs) else listOf(0 to spec.target)
        engines = lanes.map { (lane, target) ->
            lane to LiveTranslate(key, target, app.http, { event(lane, target, it) }, url = app.liveUrl()).also { engine ->
                engine.saving = spec.saver
                player?.let { p -> engine.onAudio = { pcm, rate -> p.hear(pcm, rate) } }
                engine.start()
            }
        }
        if (phone && Settings.canDrawOverlays(this)) overlay = CaptionOverlay(this) { requestStop() }.also { it.show() }
        capture(record!!)
    }

    @SuppressLint("MissingPermission")
    private fun microphone(): AudioRecord {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("Rflow needs the microphone: allow it in Android's settings.")
        }
        val size = maxOf(AudioRecord.getMinBufferSize(Pcm.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), Pcm.FRAME_BYTES * 4)
        // Voice recognition: the room as it is, without the call processing that cuts quiet voices far from the phone.
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val r = AudioRecord(source, Pcm.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (r.state == AudioRecord.STATE_INITIALIZED) return r
            r.release()
        }
        throw IllegalStateException("The microphone couldn't be opened (another app may be using it).")
    }

    /** What the phone plays (videos, podcasts), with the user's consent for this session; apps may forbid it. */
    @SuppressLint("MissingPermission")
    private fun playbackCapture(intent: Intent): AudioRecord {
        val code = intent.getIntExtra("projection_code", Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projection_data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("projection_data")
        if (code != Activity.RESULT_OK || data == null) throw IllegalStateException("Android didn't allow Rflow to hear the phone's sound.")
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = manager.getMediaProjection(code, data) ?: throw IllegalStateException("Android didn't allow Rflow to hear the phone's sound.")
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stop()
        }, null)
        projection = p
        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Pcm.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(Pcm.FRAME_BYTES * 8)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    /** Debug builds talking to a test server: a made-up voice instead of the room (the emulator's microphone is silent). */
    private val synthetic: SyntheticVoice? get() = syntheticVoice

    private var syntheticVoice: SyntheticVoice? = null

    private fun capture(r: AudioRecord) {
        if (io.github.karthiaiengineer.rflow.BuildConfig.DEBUG && (application as RflowApp).settings.testServer.isNotBlank() &&
            spec?.source == Source.ROOM) syntheticVoice = SyntheticVoice()
        capturing = true
        r.startRecording()
        captureThread = Thread({
            val frame = ByteArray(Pcm.FRAME_BYTES)
            val quiet = ByteArray(Pcm.FRAME_BYTES)
            var silentFrames = 0
            var told = false
            var lastTick = 0L
            while (capturing) {
                var got = 0
                while (got < frame.size && capturing) {
                    val n = r.read(frame, got, frame.size - got)
                    if (n < 0) { capturing = false; LiveHub.edit { it.copy(problem = "The audio stopped ($n).") }; requestStop(); break }
                    got += n
                }
                if (got < frame.size) break
                synthetic?.let { it.next(frame) }
                wav?.write(frame)
                // The room hears the voice: silence instead, so Gemini doesn't translate its own words back.
                val echo = spec?.source == Source.ROOM && player?.speaking == true
                val out = if (echo) quiet.copyOf() else frame.copyOf()
                for ((_, engine) in engines) engine.feed(out)
                // What the phone plays may be all silence: an app that forbids capture, or nothing playing.
                silentFrames = if (Pcm.silent(frame)) silentFrames + 1 else 0
                if (spec?.source == Source.PHONE && silentFrames == 80 && !told) {
                    told = true
                    LiveHub.edit { it.copy(status = "Nothing heard: play something (some apps don't allow their sound to be captured).") }
                }
                val now = System.currentTimeMillis()
                if (now - lastTick > 1000) {
                    lastTick = now
                    tick(now)
                }
            }
        }, "capture").apply { isDaemon = true; start() }
    }

    /** Once a second: what was spent and saved, and the cost saver's stop after 15 minutes without words. */
    private fun tick(now: Long) {
        val sent = engines.sumOf { it.second.sentFrames } * 0.1
        val saved = engines.sumOf { it.second.unsentSeconds }
        LiveHub.edit { it.copy(sentSeconds = sent, savedSeconds = saved, speaking = player?.speaking == true) }
        overlay?.refresh()  // the status may have changed ("Nothing heard")
        val s = spec ?: return
        if (s.saver && s.mode != Mode.MEETING) {
            val last = engines.maxOfOrNull { it.second.lastWordsAt } ?: return
            if (System.nanoTime() / 1e9 - last > AUTO_STOP_S) {
                LiveHub.edit { it.copy(problem = "Stopped after 15 minutes without words (the cost saver).") }
                requestStop()
            }
        }
    }

    private fun event(lane: Int, target: String, event: LiveEvent) {
        val conversation = spec?.mode == Mode.CONVERSATION
        when (event) {
            is LiveEvent.Heard -> if (!conversation || !Languages.same(event.language, target)) LiveHub.heard(lane, event.text)
            is LiveEvent.Translated -> LiveHub.translated(lane, event.text)
            is LiveEvent.Done -> {
                // Conversation: each side translates into one language; what is already in it is the other side's job.
                val untranslated = event.line.translation.isBlank() || Languages.same(event.line.language, target)
                if (conversation && untranslated) LiveHub.clearCurrent(lane) else {
                    LiveHub.done(lane, event.line)
                    meetingDir?.let { MeetingStore.appendLine(it, event.line) }
                }
            }
            is LiveEvent.Status -> LiveHub.edit { it.copy(status = event.text) }
            is LiveEvent.Problem -> {
                LiveHub.edit { it.copy(problem = event.text) }
                if (event.final) requestStop()
            }
        }
        overlay?.refresh()
    }

    /** From the engines' and the capture's threads: the stopping happens on the main thread, which joins them. */
    private fun requestStop() {
        main.post { stop() }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun fail(message: String) {
        LiveHub.edit { it.copy(problem = message) }
        stop()
    }

    private fun stop() {
        val wasSpec = spec ?: run { stopSelf(); return }
        spec = null
        capturing = false
        captureThread?.join(1000)
        runCatching { record?.stop() }
        record?.release()
        record = null
        engines.forEach { it.second.stop() }
        engines = emptyList()
        player?.close()
        player = null
        overlay?.hide()
        overlay = null
        projection?.stop()
        projection = null
        val dir = meetingDir
        wav?.let { w ->
            w.close()
            if (dir != null) MeetingStore.finish(dir, w.seconds)
        }
        wav = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        LiveHub.stopped()
        if (wasSpec.mode == Mode.MEETING && dir != null) (application as RflowApp).meetings.writeMinutes(dir)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (spec != null) stop()
        super.onDestroy()
    }

    private fun notification(spec: SessionSpec): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Live translation", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val title = when (spec.mode) {
            Mode.LIVE -> if (spec.source == Source.PHONE) "Translating what the phone plays" else "Live translation"
            Mode.MEETING -> "Meeting mode: recording"
            Mode.CONVERSATION -> "Conversation"
        }
        val langs = if (spec.mode == Mode.CONVERSATION) "${Languages.name(spec.target)} ⇄ ${Languages.name(spec.theirs)}"
        else "Into ${Languages.name(spec.target)}"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(langs)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val START = "io.github.karthiaiengineer.rflow.live.START"
        const val STOP = "io.github.karthiaiengineer.rflow.live.STOP"
        private const val CHANNEL = "live"
        private const val NOTE_ID = 7
        private const val AUTO_STOP_S = 15 * 60

        fun start(context: Context, spec: SessionSpec, projectionCode: Int = 0, projectionData: Intent? = null) {
            val intent = Intent(context, LiveService::class.java).setAction(START)
                .putExtra("mode", spec.mode.name).putExtra("source", spec.source.name)
                .putExtra("target", spec.target).putExtra("theirs", spec.theirs)
                .putExtra("speak", spec.speak).putExtra("saver", spec.saver)
                .putExtra("projection_code", projectionCode).putExtra("projection_data", projectionData)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveService::class.java).setAction(STOP))
        }
    }
}

/** Bursts of a voice-like tone (1.5 s) and pauses (1 s), at speech level: for testing without a microphone. */
internal class SyntheticVoice {
    private var n = 0L

    fun next(frame: ByteArray) {
        for (i in 0 until frame.size / 2) {
            val t = n++
            val on = t % 40_000 < 24_000
            val v = if (on) (3000 * Math.sin(2 * Math.PI * 180 * t / 16000.0) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 4 * t / 16000.0))).toInt() else 0
            frame[2 * i] = v.toByte(); frame[2 * i + 1] = (v shr 8).toByte()
        }
    }
}
