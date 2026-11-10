package io.github.karthiaiengineer.rflow.core

/** A finished caption line: what was heard and its translation. [lag]: seconds after the voice paused (0 = unknown). */
data class Line(val source: String, val translation: String, val language: String = "", val lag: Double = 0.0)

/**
 * Builds caption lines from Gemini Live Translate's pieces, as Rflow for Windows does (`sst/live/gemini.py`): the words
 * heard and their translation arrive piece by piece; a line ends when the model ends its turn, after a pause without new
 * words, or once a long translation and the words heard are both at a sentence end. Not thread-safe: the engine calls
 * it under its own lock.
 */
class LineBuilder {
    var source = ""
        private set
    var translation = ""
        private set
    var language = ""
        private set

    val pending get() = source.isNotEmpty() || translation.isNotEmpty()

    fun addSource(piece: String, lang: String) {
        source = join(source, piece)
        if (lang.isNotBlank()) language = lang
    }

    fun addTranslation(piece: String) {
        translation = join(translation, piece)
    }

    /** Where a long line ends, or null: (translation cut, source cut). */
    fun findCut(): Pair<Int, Int>? {
        val t = translation
        val s = source
        if (t.length < LONG_LINE || !SENTENCE_END.containsMatchIn(t)) return null
        if (SENTENCE_END.containsMatchIn(s)) return t.length to s.length
        if (t.length >= 2 * LONG_LINE) {  // the two never pause together: end at the words heard's last sentence end
            val last = SENTENCES.findAll(s).lastOrNull()?.range?.last?.plus(1)
            return t.length to (last ?: s.length)
        }
        return null
    }

    /** Ends the line (all of it, or up to the cuts: what follows begins the next line); null when there was nothing. */
    fun finish(cut: Pair<Int, Int>? = null, lag: Double = 0.0): Line? {
        val (tc, sc) = cut ?: (translation.length to source.length)
        val src = source.substring(0, sc).trim()
        val tr = translation.substring(0, tc).trim()
        source = source.substring(sc).trimStart()
        translation = translation.substring(tc).trimStart()
        val lang = language
        if (!pending) language = ""
        return if (src.isEmpty() && tr.isEmpty()) null else Line(tidy(src), tidy(tr), lang, lag)
    }

    companion object {
        const val LONG_LINE = 120
        private val SENTENCE_END = Regex("""[.!?。！？]\s*$""")
        private val SENTENCES = Regex("""[。！？!?]+|\.+(?!\w)""")
        private const val CJK = "　-〿぀-ヿㇰ-ㇿ㐀-䶿一-鿿＀-￯"
        private val CJK_GAP = Regex("""(?<=[$CJK])\s+(?=[$CJK?!])""")
        private val POSSESSIVE_GAP = Regex("""s['’]$""")
        private const val RESENT = 0.85

        /** Japanese and Chinese put no spaces between words; Google's pieces come with them. */
        fun tidy(text: String): String = CJK_GAP.replace(text, "")

        /**
         * A piece that repeats the whole line so far replaces it (also when the model revised it meanwhile); else it is
         * appended, with the space Google drops after a plural possessive.
         */
        fun join(text: String, piece: String): String {
            if (text.isNotEmpty() && (piece.startsWith(text) || piece.trimStart().startsWith(text.trimStart()) ||
                    resends(text, piece))) return piece
            if (POSSESSIVE_GAP.containsMatchIn(text) && piece.isNotEmpty() && piece[0].isLetter() && piece[0].code < 128) {
                return "$text $piece"
            }
            return text + piece
        }

        private val WORD = Regex("""\w+""")

        private fun words(text: String) = WORD.findAll(text.lowercase()).joinToString(" ") { it.value }

        /** The piece is the line so far again, revised: about as long, and its start holds nearly all of the line. */
        private fun resends(text: String, piece: String): Boolean {
            val line = words(text)
            val sent = words(piece)
            if (line.count { it == ' ' } < 2 || sent.length < 0.9 * line.length) return false
            val window = sent.take((1.2 * line.length).toInt())
            return lcs(line, window) >= RESENT * line.length
        }

        /** Longest common subsequence length: how much of `a` appears in `b`, in order. */
        private fun lcs(a: String, b: String): Int {
            var prev = IntArray(b.length + 1)
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}
