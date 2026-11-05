package com.bxane.nuvx.ui

import androidx.compose.ui.graphics.Color
import com.bxane.nuvx.core.KeyValueStore
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal object AccentPalettePreference {
    const val DEFAULT = "#FFFFFF"
    private const val SELECTED_KEY = "accentPaletteHex"
    private const val SWATCHES_KEY = "accentPaletteSwatches"
    val DEFAULT_SWATCHES = listOf(DEFAULT, "#6E91F0", "#A78BFA", "#D47DC2")

    fun read(store: KeyValueStore): String = normalize(store.get(SELECTED_KEY))

    fun readSwatches(store: KeyValueStore): List<String> {
        val saved = store.get(SWATCHES_KEY)?.split(',') ?: return DEFAULT_SWATCHES
        return saved.takeIf { it.size in 3..5 && it.all(::isValid) && it.distinct().size == it.size }
            ?.map(::normalize) ?: DEFAULT_SWATCHES
    }

    fun save(store: KeyValueStore, hex: String): String {
        val value = normalize(hex)
        store.put(SELECTED_KEY, value)
        val swatches = readSwatches(store)
        if (value !in swatches) saveSwatches(store, (swatches + value).takeLast(5))
        return value
    }

    fun saveSwatches(store: KeyValueStore, colors: List<String>): List<String> {
        val normalized = colors.map(::normalize).distinct().take(5)
        val value = normalized.takeIf { it.size in 3..5 } ?: DEFAULT_SWATCHES
        store.put(SWATCHES_KEY, value.joinToString(","))
        return value
    }

    /** Creates a four-colour light-to-deep ramp from one hue family. */
    fun generate(seed: Int): List<String> {
        val base = ((seed % 360) + 360) % 360
        val direction = if (seed and 1 == 0) 1 else -1
        val hueStep = 14 + ((seed ushr 1) and 12)
        val lightness = listOf(0.84, 0.75, 0.66, 0.57)
        return lightness.mapIndexed { index, l ->
            val chroma = 0.12 + sin(index / 3.0 * PI) * 0.07
            oklchHex(l, chroma, (base + direction * index * hueStep).toDouble())
        }
    }

    fun normalize(hex: String?): String {
        val value = hex.orEmpty().trim().removePrefix("#")
        return if (isValid(value)) "#${value.uppercase()}" else DEFAULT
    }

    fun isValid(hex: String): Boolean {
        val value = hex.trim().removePrefix("#")
        return value.length == 6 && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    }

    fun label(hex: String, swatches: List<String>): String = swatches.indexOfFirst { normalize(it) == normalize(hex) }
        .takeIf { it >= 0 }?.let { "Swatch ${it + 1}" } ?: "Custom"

    fun color(hex: String): Color = Color(0xFF000000.toInt() or normalize(hex).removePrefix("#").toInt(16))

    private fun oklchHex(lightness: Double, chroma: Double, hue: Double): String {
        val radians = hue * PI / 180.0
        val a = chroma * cos(radians)
        val b = chroma * sin(radians)
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.291485548 * b).pow(3)
        val red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val blue = -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s
        return "#${red.hexChannel()}${green.hexChannel()}${blue.hexChannel()}"
    }

    private fun Double.hexChannel(): String {
        val linear = coerceIn(0.0, 1.0)
        val srgb = if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.pow(1.0 / 2.4) - 0.055
        return (srgb.coerceIn(0.0, 1.0) * 255).roundToInt().toString(16).uppercase().padStart(2, '0')
    }
}

internal data class AccentPaletteDraft(val selectedHex: String, val swatches: List<String>) {
    fun generate(seed: Int): AccentPaletteDraft {
        val generated = AccentPalettePreference.generate(seed)
        return copy(selectedHex = generated.first(), swatches = generated)
    }

    fun apply(store: KeyValueStore): String {
        AccentPalettePreference.saveSwatches(store, swatches)
        return AccentPalettePreference.save(store, selectedHex)
    }
}
