package com.example.witches.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import com.example.witches.core.Sign

// Авторские контуры общепринятых U+2648–U+2653; координаты в квадрате 100 × 100.
private val ZodiacSymbolPaths = mapOf(
    Sign.ARIES to "M15 42 C7 4 50 3 50 45 L50 87 M85 42 C93 4 50 3 50 45",
    Sign.TAURUS to "M13 14 C13 34 29 43 50 43 C71 43 87 34 87 14 " +
        "M50 43 C80 43 80 87 50 87 C20 87 20 43 50 43 Z",
    Sign.GEMINI to "M17 15 C37 24 63 24 83 15 M17 85 C37 76 63 76 83 85 M32 21 L32 79 M68 21 L68 79",
    Sign.CANCER to "M16 36 C16 10 62 6 84 24 " +
        "M42 36 C42 53 16 53 16 36 C16 19 42 19 42 36 Z " +
        "M84 64 C84 90 38 94 16 76 " +
        "M58 64 C58 47 84 47 84 64 C84 81 58 81 58 64 Z",
    Sign.LEO to "M26 51 C8 16 55 4 65 23 C77 46 45 61 62 80 C70 94 84 85 88 73 " +
        "M39 64 C39 81 13 81 13 64 C13 47 39 47 39 64 Z",
    Sign.VIRGO to "M15 23 L15 75 M15 39 C15 16 39 16 39 39 L39 75 " +
        "M39 39 C39 16 63 16 63 39 L63 65 C63 82 76 89 88 77 " +
        "M63 42 C63 17 87 17 87 40 C87 60 66 77 52 85",
    Sign.LIBRA to "M14 62 L35 62 C19 44 31 21 50 21 C69 21 81 44 65 62 L86 62 M14 82 L86 82",
    Sign.SCORPIO to "M13 23 L13 74 M13 39 C13 16 36 16 36 39 L36 74 " +
        "M36 39 C36 16 59 16 59 39 L59 66 C59 85 75 89 88 73 M77 73 L88 73 L88 84",
    Sign.SAGITTARIUS to "M18 84 L82 20 M56 20 L82 20 L82 46 M25 43 L58 76",
    Sign.CAPRICORN to "M13 25 C21 13 30 17 30 35 L30 67 " +
        "M30 35 C30 13 55 12 58 35 C61 59 48 65 55 79 " +
        "C68 100 94 81 85 63 C77 46 58 60 55 79",
    Sign.AQUARIUS to "M12 37 L26 23 L40 37 L54 23 L68 37 L82 23 " +
        "M12 70 L26 56 L40 70 L54 56 L68 70 L82 56",
    Sign.PISCES to "M22 13 C45 32 45 68 22 87 M78 13 C55 32 55 68 78 87 M13 50 L87 50",
).mapValues { (_, pathData) -> PathParser().parsePathString(pathData).toPath() }.also { symbols ->
    check(symbols.keys == Sign.entries.toSet()) { "Для каждого знака нужен собственный символ" }
    symbols.values.forEach { path ->
        val bounds = path.getBounds()
        check(!bounds.isEmpty && bounds.left >= 0f && bounds.top >= 0f && bounds.right <= 100f && bounds.bottom <= 100f)
    }
}

internal fun DrawScope.drawZodiacSymbol(sign: Sign, color: Color) {
    val side = size.minDimension * 0.9f
    val symbolScale = side / 100f
    withTransform({
        translate((size.width - side) / 2f, (size.height - side) / 2f)
        scale(symbolScale, symbolScale, pivot = Offset.Zero)
    }) {
        drawPath(ZodiacSymbolPaths.getValue(sign), color, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
