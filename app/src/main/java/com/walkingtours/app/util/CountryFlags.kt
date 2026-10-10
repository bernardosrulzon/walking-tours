package com.walkingtours.app.util

/**
 * The flag emoji for a country name, for the city cards.
 *
 * Tours name their countries in prose ("Türkiye", "China (Macao SAR)"), so there is no ISO code to
 * build the regional-indicator pair from; the handful of countries the app ships are listed here
 * instead. A name this map does not know gets no flag rather than a wrong one, and a new country is
 * added here alongside the tour that introduces it.
 */
object CountryFlags {

    private val flags = mapOf(
        "Azerbaijan" to "🇦🇿",
        "China" to "🇨🇳",
        "China (Macao SAR)" to "🇲🇴",
        "Hong Kong" to "🇭🇰",
        "India" to "🇮🇳",
        "Nepal" to "🇳🇵",
        "Türkiye" to "🇹🇷",
    )

    /** "🇨🇳" for a known country, "" otherwise. */
    fun flag(country: String): String = flags[country.trim()] ?: ""
}
