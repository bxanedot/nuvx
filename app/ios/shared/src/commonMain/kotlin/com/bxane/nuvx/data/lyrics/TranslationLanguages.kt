package com.bxane.nuvx.data.lyrics














data class TranslationLanguage(val code: String, val fallbackName: String)






val TRANSLATION_LANGUAGES: List<TranslationLanguage> = listOf(
    TranslationLanguage("af", "Afrikaans"),
    TranslationLanguage("sq", "Albanian"),
    TranslationLanguage("am", "Amharic"),
    TranslationLanguage("ar", "Arabic"),
    TranslationLanguage("hy", "Armenian"),
    TranslationLanguage("as", "Assamese"),
    TranslationLanguage("ay", "Aymara"),
    TranslationLanguage("az", "Azerbaijani"),
    TranslationLanguage("bm", "Bambara"),
    TranslationLanguage("eu", "Basque"),
    TranslationLanguage("be", "Belarusian"),
    TranslationLanguage("bn", "Bengali"),
    TranslationLanguage("bho", "Bhojpuri"),
    TranslationLanguage("bs", "Bosnian"),
    TranslationLanguage("bg", "Bulgarian"),
    TranslationLanguage("ca", "Catalan"),
    TranslationLanguage("ceb", "Cebuano"),
    TranslationLanguage("ny", "Chichewa"),
    TranslationLanguage("zh-CN", "Chinese (Simplified)"),
    TranslationLanguage("zh-TW", "Chinese (Traditional)"),
    TranslationLanguage("co", "Corsican"),
    TranslationLanguage("hr", "Croatian"),
    TranslationLanguage("cs", "Czech"),
    TranslationLanguage("da", "Danish"),
    TranslationLanguage("nl", "Dutch"),
    TranslationLanguage("en", "English"),
    TranslationLanguage("eo", "Esperanto"),
    TranslationLanguage("et", "Estonian"),
    TranslationLanguage("fi", "Finnish"),
    TranslationLanguage("fr", "French"),
    TranslationLanguage("fy", "Frisian"),
    TranslationLanguage("gl", "Galician"),
    TranslationLanguage("ka", "Georgian"),
    TranslationLanguage("de", "German"),
    TranslationLanguage("el", "Greek"),
    TranslationLanguage("gu", "Gujarati"),
    TranslationLanguage("ht", "Haitian Creole"),
    TranslationLanguage("ha", "Hausa"),
    TranslationLanguage("haw", "Hawaiian"),
    TranslationLanguage("iw", "Hebrew"),
    TranslationLanguage("hi", "Hindi"),
    TranslationLanguage("hmn", "Hmong"),
    TranslationLanguage("hu", "Hungarian"),
    TranslationLanguage("is", "Icelandic"),
    TranslationLanguage("ig", "Igbo"),
    TranslationLanguage("id", "Indonesian"),
    TranslationLanguage("ga", "Irish"),
    TranslationLanguage("it", "Italian"),
    TranslationLanguage("ja", "Japanese"),
    TranslationLanguage("jw", "Javanese"),
    TranslationLanguage("kn", "Kannada"),
    TranslationLanguage("kk", "Kazakh"),
    TranslationLanguage("km", "Khmer"),
    TranslationLanguage("ko", "Korean"),
    TranslationLanguage("ku", "Kurdish (Kurmanji)"),
    TranslationLanguage("ky", "Kyrgyz"),
    TranslationLanguage("lo", "Lao"),
    TranslationLanguage("la", "Latin"),
    TranslationLanguage("lv", "Latvian"),
    TranslationLanguage("lt", "Lithuanian"),
    TranslationLanguage("lb", "Luxembourgish"),
    TranslationLanguage("mk", "Macedonian"),
    TranslationLanguage("mg", "Malagasy"),
    TranslationLanguage("ms", "Malay"),
    TranslationLanguage("ml", "Malayalam"),
    TranslationLanguage("mt", "Maltese"),
    TranslationLanguage("mi", "Maori"),
    TranslationLanguage("mr", "Marathi"),
    TranslationLanguage("mn", "Mongolian"),
    TranslationLanguage("my", "Myanmar (Burmese)"),
    TranslationLanguage("ne", "Nepali"),
    TranslationLanguage("no", "Norwegian"),
    TranslationLanguage("ps", "Pashto"),
    TranslationLanguage("fa", "Persian"),
    TranslationLanguage("pl", "Polish"),
    TranslationLanguage("pt", "Portuguese"),
    TranslationLanguage("pa", "Punjabi"),
    TranslationLanguage("ro", "Romanian"),
    TranslationLanguage("ru", "Russian"),
    TranslationLanguage("sm", "Samoan"),
    TranslationLanguage("gd", "Scots Gaelic"),
    TranslationLanguage("sr", "Serbian"),
    TranslationLanguage("st", "Sesotho"),
    TranslationLanguage("sn", "Shona"),
    TranslationLanguage("sd", "Sindhi"),
    TranslationLanguage("si", "Sinhala"),
    TranslationLanguage("sk", "Slovak"),
    TranslationLanguage("sl", "Slovenian"),
    TranslationLanguage("so", "Somali"),
    TranslationLanguage("es", "Spanish"),
    TranslationLanguage("su", "Sundanese"),
    TranslationLanguage("sw", "Swahili"),
    TranslationLanguage("sv", "Swedish"),
    TranslationLanguage("tl", "Tagalog"),
    TranslationLanguage("tg", "Tajik"),
    TranslationLanguage("ta", "Tamil"),
    TranslationLanguage("te", "Telugu"),
    TranslationLanguage("th", "Thai"),
    TranslationLanguage("tr", "Turkish"),
    TranslationLanguage("uk", "Ukrainian"),
    TranslationLanguage("ur", "Urdu"),
    TranslationLanguage("ug", "Uyghur"),
    TranslationLanguage("uz", "Uzbek"),
    TranslationLanguage("vi", "Vietnamese"),
    TranslationLanguage("cy", "Welsh"),
    TranslationLanguage("xh", "Xhosa"),
    TranslationLanguage("yi", "Yiddish"),
    TranslationLanguage("yo", "Yoruba"),
    TranslationLanguage("zu", "Zulu"),
)










val ROMANIZATION_LANGUAGES: List<TranslationLanguage> = listOf(
    TranslationLanguage("ja", "Japanese"),
    TranslationLanguage("ko", "Korean"),
    TranslationLanguage("zh-CN", "Chinese (Simplified)"),
    TranslationLanguage("zh-TW", "Chinese (Traditional)"),
    TranslationLanguage("ru", "Russian"),
    TranslationLanguage("ar", "Arabic"),
    TranslationLanguage("he", "Hebrew"),
    TranslationLanguage("hi", "Hindi"),
    TranslationLanguage("th", "Thai"),
    TranslationLanguage("el", "Greek"),
    TranslationLanguage("hy", "Armenian"),
    TranslationLanguage("ka", "Georgian"),
    TranslationLanguage("am", "Amharic"),
    TranslationLanguage("bn", "Bengali"),
    TranslationLanguage("ta", "Tamil"),
    TranslationLanguage("te", "Telugu"),
    TranslationLanguage("mr", "Marathi"),
    TranslationLanguage("gu", "Gujarati"),
    TranslationLanguage("kn", "Kannada"),
    TranslationLanguage("ml", "Malayalam"),
    TranslationLanguage("pa", "Punjabi"),
    TranslationLanguage("ur", "Urdu"),
    TranslationLanguage("fa", "Persian"),
    TranslationLanguage("vi", "Vietnamese"),
    TranslationLanguage("km", "Khmer"),
    TranslationLanguage("lo", "Lao"),
    TranslationLanguage("my", "Myanmar (Burmese)"),
    TranslationLanguage("si", "Sinhala"),
    TranslationLanguage("ne", "Nepali"),
)









internal fun isRomanisable(text: String): Boolean =
    text.any { ch -> isNonLatinLetter(ch) }
