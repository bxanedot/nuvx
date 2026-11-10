package com.bxane.nuvx.data

import java.util.Locale

object LocaleTags {
    const val ZH_HANT = "zh-Hant"
    const val ZH_HANS = "zh-Hans"
    const val ZH_TW_WIRE = "zh-TW"
    const val ZH_CN_WIRE = "zh-CN"

    fun normalizeAppTag(raw: String?): String {
        val tag = raw?.trim()?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return "en"
        val lower = tag.lowercase(Locale.ROOT)
        if (lower.startsWith("zh")) {
            if ("hant" in lower) return ZH_HANT
            if ("hans" in lower) return ZH_HANS
            if ("tw" in lower || "hk" in lower || "mo" in lower) return ZH_HANT
            if ("cn" in lower || "sg" in lower || "my" in lower) return ZH_HANS
            return ZH_HANS
        }
        val base = Locale.forLanguageTag(tag).language.lowercase(Locale.ROOT).ifBlank { lower.substringBefore('-') }
        return when (base) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> base.ifBlank { "en" }
        }
    }

    fun systemTag(): String = normalizeAppTag(Locale.getDefault().toLanguageTag())

    fun ytHl(appTag: String): String {
        val norm = normalizeAppTag(appTag)
        return when (norm) {
            ZH_HANT -> ZH_TW_WIRE
            ZH_HANS -> ZH_CN_WIRE
            else -> norm.substringBefore('-')
        }
    }

    fun ytGl(appTag: String): String {
        return when (normalizeAppTag(appTag)) {
            ZH_HANT -> "TW"
            ZH_HANS -> "SG"
            else -> "US"
        }
    }

    fun ytSearchGl(appTag: String): String? = when (normalizeAppTag(appTag)) {
        ZH_HANT, ZH_HANS -> ytGl(appTag)
        else -> null
    }

    fun acceptLanguage(appTag: String): String {
        return when (normalizeAppTag(appTag)) {
            ZH_HANT -> "zh-TW,zh-Hant;q=0.9,zh;q=0.8,en-US;q=0.7,en;q=0.6"
            ZH_HANS -> "zh-CN,zh-Hans;q=0.9,zh;q=0.8,en-US;q=0.7,en;q=0.6"
            "en" -> "en-US,en;q=0.9"
            else -> {
                val base = normalizeAppTag(appTag).substringBefore('-')
                "$base,en-US;q=0.8,en;q=0.7"
            }
        }
    }

    fun translationWireTarget(tag: String): String {
        val t = tag.trim().replace('_', '-')
        if (t.equals("zh-TW", ignoreCase = true) || t.equals("zh-Hant", ignoreCase = true)) return ZH_TW_WIRE
        if (t.equals("zh-CN", ignoreCase = true) || t.equals("zh-Hans", ignoreCase = true)) return ZH_CN_WIRE
        val norm = normalizeAppTag(t)
        return when (norm) {
            ZH_HANT -> ZH_TW_WIRE
            ZH_HANS -> ZH_CN_WIRE
            else -> t.ifBlank { "en" }
        }
    }

    fun sameLanguageScriptAware(first: String, second: String): Boolean {
        fun canon(tag: String): String {
            val t = tag.trim().replace('_', '-')
            val lower = t.lowercase(Locale.ROOT)
            if (lower.startsWith("zh")) {
                if ("tw" in lower || "hant" in lower || "hk" in lower || "mo" in lower) return "zh-hant"
                if ("cn" in lower || "hans" in lower || "sg" in lower) return "zh-hans"
                return "zh"
            }
            return Locale.forLanguageTag(t).language.lowercase(Locale.ROOT).ifBlank { lower.substringBefore('-') }
        }
        return canon(first) == canon(second)
    }

    fun isChineseTag(tag: String): Boolean =
        tag.trim().replace('_', '-').lowercase(Locale.ROOT).startsWith("zh")
}
