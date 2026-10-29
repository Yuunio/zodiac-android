package com.example.witches.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random

class GameRulesTest {
    private data class OriginalCard(val id: Int, val key: String, val category: String, val guardian: Boolean)
    private val original = resource("deck.tsv").map { line ->
        val columns = line.split('\t')
        OriginalCard(columns[0].toInt(), columns[1], columns[2], columns[3].toBoolean())
    }

    @Test fun deckMatchesIndependentlyExtractedOriginal() {
        assertEquals(48, Deck.cards.size)
        original.forEach { expected ->
            val actual = Deck.card(expected.id)
            assertEquals(expected.key, actual.type.key)
            assertEquals(expected.key.substringBefore('_'), actual.type.sign.name.lowercase())
            assertEquals(expected.category, actual.type.group.name.lowercase())
            assertEquals(expected.guardian, actual.type.guardian != null)
        }
        assertNotEquals(Deck.card(1).id, Deck.card(13).id)
        assertEquals(Deck.card(1).type, Deck.card(13).type)
        assertEquals(Guardian.WILL, Deck.card(40).type.guardian)
        assertEquals(Guardian.CORNELIA, Deck.card(41).type.guardian)
        assertEquals(Guardian.IRMA, Deck.card(44).type.guardian)
        assertEquals(Guardian.TARANEE, Deck.card(38).type.guardian)
        assertEquals(Guardian.HAY_LIN, Deck.card(47).type.guardian)
    }

    @Test fun thresholdsMatchIndependentOriginalCases() {
        resource("scores.tsv").forEach { line ->
            val (stats, expected) = line.split('\t')
            val counts = stats.split(',').associate { field -> field.substringBefore(':') to field.substringAfter(':').toInt() }
            assertEquals(stats, expected.toInt(), GameRules.combinationsForCounts(counts).sumOf { it.points })
        }
    }

    @Test fun combinationsMatchOriginalIncrementTableForCapturedSubsets() {
        val increments = resource("increments.tsv").map { it.split('\t') }
        val random = Random(283954)
        repeat(500) {
            val captured = original.shuffled(kotlin.random.Random(random.nextLong())).take(random.nextInt(49))
            val counts = captured.groupingBy { it.category }.eachCount() + ("girls" to captured.count { it.guardian })
            val expected = increments.filter { (counts[it[0]] ?: 0) >= it[1].toInt() }.sumOf { it[2].toInt() }
            assertEquals(expected, GameRules.combinations(captured.map { it.id }).sumOf { it.points })
        }
        assertEquals(890, GameRules.combinations((1..48).toList()).sumOf { it.points })
    }

    private fun resource(name: String): List<String> =
        requireNotNull(javaClass.getResourceAsStream("/original/$name")).bufferedReader().use { it.readLines().filter(String::isNotBlank) }
}
