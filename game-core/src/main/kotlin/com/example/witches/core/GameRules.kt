package com.example.witches.core

object GameRules {
    fun combinations(captured: List<Int>): List<Combination> {
        val types = captured.map { Deck.card(it).type }
        val counts = Group.entries.associate { group ->
            group.name.lowercase() to types.count { it.group == group }
        } + ("girls" to types.count { it.guardian != null })
        return combinationsForCounts(counts)
    }

    // The original stores incremental thresholds; the UI and score use one cumulative value per group.
    internal fun combinationsForCounts(counts: Map<String, Int>): List<Combination> = buildList {
        val simple = counts["simple"] ?: 0
        if (simple >= 10) add(Combination("simple_$simple", "Простые карты: $simple", 40 + (simple - 10) * 10))
        if ((counts["triangle"] ?: 0) >= 3) add(Combination("triangle_3", "Треугольник", 50))
        val square = counts["square"] ?: 0
        if (square >= 3) add(Combination("square_${minOf(square, 4)}", "Квадрат: ${minOf(square, 4)} из 4", if (square >= 4) 80 else 40))
        val pentagon = counts["pentagon"] ?: 0
        if (pentagon >= 3) add(Combination("pentagon_${minOf(pentagon, 5)}", "Пятиугольник: ${minOf(pentagon, 5)} из 5", when {
            pentagon >= 5 -> 100
            pentagon == 4 -> 50
            else -> 30
        }))
        for (group in listOf(Group.FIRE, Group.EARTH, Group.WATER, Group.AIR)) {
            val key = group.name.lowercase()
            if ((counts[key] ?: 0) >= 3) add(Combination("${key}_3", group.label, 70))
        }
        val girls = counts["girls"] ?: 0
        if (girls >= 3) add(Combination("girls_${minOf(girls, 5)}", "Чародейки: ${minOf(girls, 5)} из 5", when {
            girls >= 5 -> 200
            girls == 4 -> 150
            else -> 70
        }))
    }
}
