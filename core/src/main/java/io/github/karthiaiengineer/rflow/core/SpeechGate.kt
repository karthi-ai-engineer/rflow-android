package io.github.karthiaiengineer.rflow.core

/**
 * The cost saver's gate: Google bills every second streamed, so only speech is sent. A frame is speech when it is
 * clearly louder than the room's own noise (tracked as it changes: an air conditioner, a café) and not near silence;
 * the gate stays open [HANGOVER] frames after the last such frame, so words aren't cut between breaths, and the
 * [PREROLL_FRAMES] before it opens are sent first, so the first syllable is heard. A plain gate, not a voice model: it
 * may let a door slam through (a few cents), but never keeps speech back for long.
 */
class SpeechGate(
    private val above: Double = 10.0,  // dB over the room's noise
    private val floorDb: Double = -52.0,  // never "speech" below this
) {
    private var noise = -60.0  // the room's noise, dBFS
    private var open = 0  // frames the gate stays open
    var openings = 0
        private set

    /** True: send this 100 ms frame. */
    fun process(frame: ByteArray): Boolean {
        val db = Pcm.dbfs(Pcm.level(frame))
        // The noise follows quiet frames quickly and loud ones slowly, so speech doesn't become "noise".
        noise = if (db < noise) noise + (db - noise) * 0.3 else noise + (db - noise) * 0.01
        val speech = db > floorDb && db > noise + above
        if (speech) {
            if (open == 0) openings++
            open = HANGOVER
        } else if (open > 0) {
            open--
        }
        return open > 0
    }

    companion object {
        const val PREROLL_FRAMES = 10  // 1 s
        const val HANGOVER = 15  // 1.5 s
    }
}
