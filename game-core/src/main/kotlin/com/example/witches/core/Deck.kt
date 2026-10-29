package com.example.witches.core

/** IDs follow zodiac_cards.all_cards, including its two instances of each simple sign. */
object Deck {
    private val simpleSigns = Sign.entries.toList()
    private fun type(sign: Sign, group: Group, guardian: Guardian? = null): CardType {
        val key = sign.name.lowercase() + if (group == Group.SIMPLE) "" else "_${group.name.lowercase()}"
        return CardType(key, sign, group, guardian)
    }

    val cards: List<Card> = buildList<CardType> {
        repeat(2) { simpleSigns.forEach { add(type(it, Group.SIMPLE)) } }
        listOf(Sign.CAPRICORN, Sign.TAURUS, Sign.ARIES).forEach { add(type(it, Group.TRIANGLE)) }
        listOf(Sign.SAGITTARIUS, Sign.VIRGO, Sign.PISCES, Sign.CANCER).forEach { add(type(it, Group.SQUARE)) }
        listOf(Sign.AQUARIUS, Sign.LIBRA, Sign.GEMINI, Sign.SCORPIO, Sign.LEO).forEach { add(type(it, Group.PENTAGON)) }
        add(type(Sign.SAGITTARIUS, Group.FIRE))
        add(type(Sign.ARIES, Group.FIRE, Guardian.TARANEE))
        add(type(Sign.LEO, Group.FIRE))
        add(type(Sign.CAPRICORN, Group.EARTH, Guardian.WILL))
        add(type(Sign.TAURUS, Group.EARTH, Guardian.CORNELIA))
        add(type(Sign.VIRGO, Group.EARTH))
        add(type(Sign.SCORPIO, Group.WATER))
        add(type(Sign.PISCES, Group.WATER, Guardian.IRMA))
        add(type(Sign.CANCER, Group.WATER))
        add(type(Sign.AQUARIUS, Group.AIR))
        add(type(Sign.GEMINI, Group.AIR, Guardian.HAY_LIN))
        add(type(Sign.LIBRA, Group.AIR))
    }.mapIndexed { index, type -> Card(index + 1, type) }

    fun card(id: Int): Card {
        require(id in 1..cards.size) { "Неизвестная карта: $id" }
        return cards[id - 1]
    }
}
