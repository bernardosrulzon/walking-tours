package com.walkingtours.app.ai

/**
 * What kind of walker this is, asked once and remembered.
 *
 * The seven answers are the app's first question because everything downstream — which guides are
 * offered, how the chosen one talks, what they linger on — follows from it. [brief] is what the
 * model is told; [emoji] and [label] are what the walker taps.
 */
enum class ExplorerType(
    val id: String,
    val emoji: String,
    val label: String,
    val brief: String,
) {
    HISTORY(
        "history", "\uD83C\uDFDB\uFE0F", "History",
        "imperial history, dates, dynasties and how the past stacks up",
    ),
    STRANGE(
        "strange", "\uD83D\uDC7B", "Strange & mysterious",
        "legends, unsolved mysteries, dark corners and eerie details",
    ),
    FOOD(
        "food", "\uD83C\uDF5C", "Food & local life",
        "food, markets, everyday life and the people who live here now",
    ),
    HIDDEN(
        "hidden", "\uD83D\uDCF8", "Hidden details",
        "the small things most people walk past, and secrets hiding in plain sight",
    ),
    ART(
        "art", "\uD83C\uDFA8", "Art & architecture",
        "art, design, buildings, materials and craftsmanship",
    ),
    POWER(
        "power", "\u2694\uFE0F", "Wars & power",
        "war, conquest, sieges, politics and the exercise of power",
    ),
    PEOPLE(
        "people", "\u2764\uFE0F", "Stories about people",
        "the human stories: love, ambition, grief, rivalry and ordinary lives",
    );

    companion object {
        fun fromId(id: String?): ExplorerType? = entries.firstOrNull { it.id == id }
    }
}
