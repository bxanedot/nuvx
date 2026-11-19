package com.licht.nuvx

import com.licht.nuvx.ui.components.SUPPORTED_LANGUAGES
import com.licht.nuvx.ui.components.languageDisplayNameRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationTest {

    @Test
    fun supportedLanguages_containsVietnamese() {
        val viLanguage = SUPPORTED_LANGUAGES.firstOrNull { it.tag == "vi" }
        assertNotNull("Vietnamese language must be in SUPPORTED_LANGUAGES", viLanguage)
        assertEquals(R.string.vietnamese, viLanguage?.nameRes)
        assertEquals(R.string.vietnamese, languageDisplayNameRes("vi"))
    }

    @Test
    fun localesConfig_matchesSupportedLanguages() {
        val projectRoot = File(".").canonicalFile
        val resDir = if (File(projectRoot, "app/src/main/res").exists()) {
            File(projectRoot, "app/src/main/res")
        } else {
            File(projectRoot, "src/main/res")
        }
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        assertTrue("locales_config.xml must exist", localesConfigFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(localesConfigFile)
        val localeNodes = doc.getElementsByTagName("locale")

        val configuredLocales = mutableListOf<String>()
        for (i in 0 until localeNodes.length) {
            val node = localeNodes.item(i)
            val name = node.attributes.getNamedItem("android:name")?.nodeValue
            if (name != null) configuredLocales.add(name)
        }

        assertTrue("locales_config.xml must include 'vi'", configuredLocales.contains("vi"))

        for (lang in SUPPORTED_LANGUAGES) {
            assertTrue(
                "locales_config.xml must include '${lang.tag}'",
                configuredLocales.contains(lang.tag),
            )
        }
    }
    @Test
    fun traditionalChineseAutomixOverview_hasLocalizedString() {
        val projectRoot = File(".").canonicalFile
        val resDir = if (File(projectRoot, "app/src/main/res").exists()) {
            File(projectRoot, "app/src/main/res")
        } else {
            File(projectRoot, "src/main/res")
        }
        val stringsFile = File(resDir, "values-b+zh+Hant/strings.xml")
        assertTrue("Generic Traditional Chinese resources must exist", stringsFile.exists())

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val nodes = document.getElementsByTagName("string")
        val translation = (0 until nodes.length)
            .asSequence()
            .map { nodes.item(it) }
            .firstOrNull { node -> node.attributes?.getNamedItem("name")?.nodeValue == "automix_overview" }
            ?.textContent
            ?.trim()

        assertEquals("Automix 概覽", translation)
    }

}
