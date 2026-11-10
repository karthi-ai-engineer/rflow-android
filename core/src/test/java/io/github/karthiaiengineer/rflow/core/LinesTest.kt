package io.github.karthiaiengineer.rflow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LinesTest {
    @Test fun piecesAreAppended() {
        assertEquals("We're moving the release", LineBuilder.join("We're moving", " the release"))
    }

    @Test fun aPieceThatRepeatsTheLineReplacesIt() {
        assertEquals("We are moving it.", LineBuilder.join("We are moving", "We are moving it."))
        // revised meanwhile: a word changed, the rest the same
        assertEquals("we are moving the release to Thursday",
            LineBuilder.join("we are moving the relase to", "we are moving the release to Thursday"))
    }

    @Test fun theSpaceAfterAPluralPossessiveComesBack() {
        assertEquals("my parents' house", LineBuilder.join("my parents'", "house"))
    }

    @Test fun japaneseLosesGooglesSpaces() {
        assertEquals("来週のリリースを木曜日に移します。", LineBuilder.tidy("来週の リリースを 木曜日に 移します 。"))
        assertEquals("Hello world", LineBuilder.tidy("Hello world"))
    }

    @Test fun aLineFinishes() {
        val b = LineBuilder()
        b.addSource("こんにちは", "ja")
        b.addTranslation("Hello")
        assertTrue(b.pending)
        assertEquals(Line("こんにちは", "Hello", "ja"), b.finish())
        assertFalse(b.pending)
        assertNull(b.finish())
    }

    @Test fun aLongLineEndsWhereBothAreAtASentenceEnd() {
        val b = LineBuilder()
        b.addTranslation("x".repeat(130) + ".")
        b.addSource("まだ")
        assertNull(b.findCut())
        b.addSource("終わりました。")
        val cut = b.findCut()!!
        val line = b.finish(cut)!!
        assertTrue(line.translation.endsWith("."))
        assertFalse(b.pending)
    }

    private fun LineBuilder.addSource(text: String) = addSource(text, "ja")

    @Test fun theGateSendsSpeechAndHoldsTheRoom() {
        val gate = SpeechGate()
        val room = tone(0.002)  // about -57 dBFS: a quiet room
        val voice = tone(0.1)  // -23 dBFS
        repeat(50) { assertFalse(gate.process(room)) }
        assertTrue(gate.process(voice))
        repeat(SpeechGate.HANGOVER - 1) { assertTrue(gate.process(room)) }  // a breath doesn't close it
        assertFalse(gate.process(room))
        assertEquals(1, gate.openings)
    }

    @Test fun silenceIsSilence() {
        assertTrue(Pcm.silent(ByteArray(3200)))
        assertFalse(Pcm.silent(tone(0.1)))
    }

    @Test fun aWavIsWrittenAndRepaired() {
        val file = File.createTempFile("room", ".wav")
        WavWriter(file).use { w -> repeat(10) { w.write(tone(0.1)) } }
        assertEquals(44 + 32_000L, file.length())
        assertEquals(1.0, WavWriter.seconds(file), 1e-9)
        val header = file.readBytes().copyOfRange(0, 44)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals(32_000, java.nio.ByteBuffer.wrap(header, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        // cut short: the header says 0 bytes until repaired
        file.outputStream().use { it.write(WavWriter.header(16_000, 0) + ByteArray(6400)) }
        WavWriter.repair(file)
        assertEquals(6400, java.nio.ByteBuffer.wrap(file.readBytes(), 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        file.delete()
    }

    @Test fun languagesMatchWithOrWithoutRegion() {
        assertTrue(Languages.same("ja-JP", "ja"))
        assertFalse(Languages.same("en", "ja"))
        assertFalse(Languages.same("", "ja"))
        assertEquals("Japanese", Languages.name("ja"))
    }

    companion object {
        /** 100 ms of a 440 Hz tone at [amplitude] (1.0 = full scale). */
        fun tone(amplitude: Double): ByteArray {
            val b = ByteArray(Pcm.FRAME_BYTES)
            for (i in 0 until b.size / 2) {
                val v = (amplitude * 32767 * Math.sin(2 * Math.PI * 440 * i / 16000.0)).toInt()
                b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
            }
            return b
        }
    }
}
