package com.walkingtours.app.audio

/**
 * Pronunciation help for an English text-to-speech voice reading Turkish place names.
 *
 * Android's TTS engines cannot be given phonemes or SSML, so the only reliable lever is to hand
 * them a respelled string. If we simply spoke a respelling, the on-screen transcript and the audio
 * would disagree and the word highlighting would drift out of step.
 *
 * [SpokenScript] solves that: it builds the respelled text *and* a segment map from respelled
 * character offsets back to the original text. The engine speaks the respelling, and when the
 * engine reports "I am now reading characters 412 to 419", we translate that back into the
 * coordinates of the prose the user is looking at. So the audio sounds right and the highlight
 * stays exact.
 */
internal object PronunciationDictionary {

    /** Keys are lowercase; values are what an English voice should actually say. */
    private val words: Map<String, String> = mapOf(
        // People
        "hürrem" to "Hoorrem",
        "hurrem" to "Hoorrem",
        "suleiman" to "Soo-lay-mahn",
        "süleyman" to "Soo-lay-mahn",
        "mehmed" to "Meh-met",
        "abdülhamid" to "Ab-dool-hah-mid",
        "abdulhamid" to "Ab-dool-hah-mid",
        "abdülmecid" to "Ab-dool-meh-jid",
        "abdulmecid" to "Ab-dool-meh-jid",
        "sinan" to "See-nahn",
        "mimar" to "Mee-mar",
        "hoca" to "Hoja",
        "paşa" to "Pasha",
        "pasa" to "Pasha",
        "valide" to "Vah-lee-deh",
        "gülersoy" to "Goo-ler-soy",
        "gulersoy" to "Goo-ler-soy",
        "korutürk" to "Ko-roo-turk",
        "gyllius" to "Gill-ee-us",
        "thutmose" to "Thoot-moh-seh",
        "theodosius" to "Thee-oh-doh-shus",
        "constantine" to "Kon-stan-teen",

        // Places
        "soğukçeşme" to "Soh-ook chesh-meh",
        "sogukcesme" to "Soh-ook chesh-meh",
        "gülhane" to "Gool-hah-neh",
        "gulhane" to "Gool-hah-neh",
        "eminönü" to "Eh-mee-nur-noo",
        "eminonu" to "Eh-mee-nur-noo",
        "ayasofya" to "Ah-yah-soh-fyah",
        "topkapı" to "Top-kah-puh",
        "topkapi" to "Top-kah-puh",
        "sultanahmet" to "Sool-tah-nah-met",
        "yerebatan" to "Yeh-reh-bah-tahn",
        "sarnıcı" to "Sar-nuh-juh",
        "mısır" to "Muh-suhr",
        "misir" to "Muh-suhr",
        "çarşısı" to "Char-shuh-suh",
        "carsisi" to "Char-shuh-suh",
        "sirkeci" to "Seer-keh-jee",
        "üsküdar" to "Oos-koo-dar",
        "uskudar" to "Oos-koo-dar",
        "kadıköy" to "Kah-duh-koy",
        "kadikoy" to "Kah-duh-koy",
        "i̇znik" to "Iz-nik",
        "iznik" to "Iz-nik",
        "i̇stanbul" to "Iss-tan-bool",
        "istanbul" to "Iss-tan-bool",
        "galata" to "Gah-lah-tah",
        "bosphorus" to "Boss-for-us",
        "marmaray" to "Mar-mah-rye",
        "mahmutpaşa" to "Mah-moot-pah-shah",
        "mahmutpasa" to "Mah-moot-pah-shah",
        "köşkü" to "Kosh-koo",
        "kiosk" to "kee-osk",
        "kiosks" to "kee-osks",
        "zeuxippus" to "Zook-sip-us",
        "vakvak" to "Vahk-vahk",
        "tughra" to "Too-rah",
        "divan" to "Dee-vahn",
        "harem" to "Hah-rem",
        "lokantas" to "lo-kahn-tas",
        "ramazan" to "Rah-mah-zahn",
        "bayram" to "By-rahm",
        "çay" to "chai",
        "çeşmesi" to "chesh-meh-see",
        "cesmesi" to "chesh-meh-see",
        "alman" to "Al-mahn",
        "balik" to "bah-luk",
        "ekmek" to "ek-mek",
        "kurukahveci" to "Koo-roo-kah-veh-jee",
        "efendi" to "Eh-fen-dee",
        "herodotus" to "Her-od-oh-tus",
        "plataea" to "Plah-tay-ah",
        "karnak" to "Kar-nak",
    )

    fun lookup(word: String): String? = words[word.lowercase(java.util.Locale.ROOT)]
}

/**
 * The text actually sent to the speech engine, plus a map back to the original prose.
 */
internal class SpokenScript private constructor(
    val spokenText: String,
    private val segments: List<Segment>,
) {
    private data class Segment(
        val spokenStart: Int,
        val spokenEnd: Int,
        val displayStart: Int,
        val displayEnd: Int,
    )

    /**
     * Translate a range reported by the speech engine into a range in the displayed text.
     * Returns null when the range covers no letters, e.g. punctuation or a pause.
     */
    fun toDisplayRange(spokenFrom: Int, spokenTo: Int): IntRange? {
        var from: Int? = null
        var to: Int? = null
        for (segment in segments) {
            if (segment.spokenEnd > spokenFrom && segment.spokenStart < spokenTo) {
                if (from == null || segment.displayStart < from) from = segment.displayStart
                if (to == null || segment.displayEnd > to) to = segment.displayEnd
            }
        }
        val f = from ?: return null
        val t = to ?: return null
        return f until maxOf(t, f + 1)
    }

    /**
     * The spoken-text offset that corresponds to a given offset in the displayed prose, used to
     * resume a paused narration at the right place.
     */
    fun spokenOffsetForDisplay(displayOffset: Int): Int {
        val segment = segments.firstOrNull { it.displayEnd > displayOffset } ?: return spokenText.length
        return segment.spokenStart
    }

    companion object {
        fun build(displayText: String): SpokenScript {
            val spoken = StringBuilder()
            val segments = ArrayList<Segment>()
            var i = 0
            while (i < displayText.length) {
                val c = displayText[i]
                if (c.isLetter()) {
                    val wordStart = i
                    while (i < displayText.length &&
                        (displayText[i].isLetter() || displayText[i] == '\'' || displayText[i] == '’')
                    ) {
                        i++
                    }
                    val wordEnd = i
                    val original = displayText.substring(wordStart, wordEnd)
                    val replacement = PronunciationDictionary.lookup(original) ?: original
                    val spokenStart = spoken.length
                    spoken.append(replacement)
                    segments += Segment(spokenStart, spoken.length, wordStart, wordEnd)
                } else {
                    spoken.append(c)
                    i++
                }
            }
            return SpokenScript(spoken.toString(), segments)
        }
    }
}
