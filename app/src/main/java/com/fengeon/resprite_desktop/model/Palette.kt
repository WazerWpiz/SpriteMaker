package com.fengeon.resprite_desktop.model

import android.graphics.Color

/** A palette: ordered list of ARGB colors with a name. */
data class Palette(
    val name: String,
    val colors: List<Int>
) {
    companion object {
        fun fromHex(name: String, hexes: List<String>): Palette = Palette(
            name = name,
            colors = hexes.map { hexToArgb(it) }
        )

        fun hexToArgb(hex: String): Int {
            val h = hex.trim().removePrefix("#")
            return when (h.length) {
                3 -> {
                    val r = h[0].digitToInt(16) * 17
                    val g = h[1].digitToInt(16) * 17
                    val b = h[2].digitToInt(16) * 17
                    Color.rgb(r, g, b)
                }
                6 -> Color.rgb(
                    h.substring(0, 2).toInt(16),
                    h.substring(2, 4).toInt(16),
                    h.substring(4, 6).toInt(16)
                )
                8 -> h.toLong(16).toInt()
                else -> 0xFF000000.toInt()
            }
        }

        val COMMODORE_64 = fromHex(
            "Commodore 64",
            listOf(
                "#000000", "#FFFFFF", "#880000", "#AAFFEE",
                "#CC44CC", "#00CC55", "#0000AA", "#EEEE77",
                "#DD8855", "#664400", "#FF7777", "#333333",
                "#777777", "#AAFF66", "#0088FF", "#BBBBBB"
            )
        )

        val GAMEBOY = fromHex(
            "Game Boy",
            listOf("#0F380F", "#306230", "#8BAC0F", "#9BBC0F")
        )

        val PICO_8 = fromHex(
            "PICO-8",
            listOf(
                "#000000", "#1D2B53", "#7E2553", "#008751",
                "#AB5236", "#5F574F", "#C2C3C7", "#FFF1E8",
                "#FF004D", "#FFA300", "#FFEC27", "#00E436",
                "#29ADFF", "#83769C", "#FF77A8", "#FFCCAA"
            )
        )

        val GRAYSCALE = fromHex(
            "Grayscale",
            (0..15).map { v -> String.format("#%02X%02X%02X", v * 17, v * 17, v * 17) }
        )

        val MS_PAINT = fromHex(
            "MS Paint",
            listOf(
                "#000000", "#808080", "#800000", "#808000",
                "#008000", "#008080", "#000080", "#800080",
                "#808040", "#004040", "#0080FF", "#004080",
                "#8000FF", "#804000", "#FFFFFF", "#C0C0C0",
                "#FF0000", "#FFFF00", "#00FF00", "#00FFFF",
                "#0000FF", "#FF00FF", "#FFFF80", "#00FF80",
                "#80FFFF", "#8080FF", "#FF0080", "#FF8040"
            )
        )

        val SYSTEM_MINI_16 = fromHex(
            "System Mini 16",
            listOf(
                "#000000", "#FFFFFF", "#7F7F7F", "#C3C3C3",
                "#880015", "#ED1C24", "#FF7F27", "#FFF200",
                "#22B14C", "#00A2E8", "#3F48CC", "#A349A4",
                "#B97A57", "#FFAEC9", "#FFC90E", "#EFE4B0"
            )
        )

        val BUILT_INS: List<Palette> = listOf(
            COMMODORE_64, GAMEBOY, PICO_8, GRAYSCALE, MS_PAINT, SYSTEM_MINI_16
        )
    }
}
