package io.ensomi.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourcesTest {
    private val sources = listOf("main", "recognition")
    private val locales = listOf("b+zh+Hans", "ja")
    private val exceptions = setOf("app_name", "settings_for_lucky", "lucky_title", "lucky_in_memory", "lucky_dedication")

    @Test
    fun everyUserFacingResourceHasBothTranslationsWithMatchingArguments() {
        sources.forEach { source ->
            val defaults = resources(source, "values").filterValues { it.getAttribute("translatable") != "false" }
            locales.forEach { locale ->
                val translated = resources(source, "values-$locale")
                assertEquals("$source/$locale resource coverage", defaults.keys, translated.keys)
                defaults.forEach { (key, default) ->
                    val actual = translated.getValue(key)
                    assertEquals(key, default.tagName, actual.tagName)
                    assertFalse(key, actual.getAttribute("translatable") == "false")
                    val expectedText = if (default.tagName == "plurals") other(default) else default.textContent
                    val texts = if (actual.tagName == "plurals") children(actual).map { it.textContent } else listOf(actual.textContent)
                    texts.forEach { text ->
                        assertTrue("$source/$locale/$key is empty", text.isNotBlank())
                        assertEquals("$source/$locale/$key format arguments", arguments(expectedText), arguments(text))
                    }
                    if (actual.tagName == "plurals") other(actual)
                }
            }
        }
    }

    @Test
    fun onlyTheBrandAndDedicationAreExempt() {
        val exempt = sources.flatMap { source ->
            resources(source, "values").filterValues { it.getAttribute("translatable") == "false" }.keys
        }.toSet()
        assertEquals(exceptions, exempt)
        assertEquals("Ensomi", resources("main", "values").getValue("app_name").textContent)
        assertEquals("For Lucky", resources("main", "values").getValue("settings_for_lucky").textContent)
    }

    @Test
    fun systemLanguagePickerDeclaresOnlyTheSupportedLanguages() {
        val config = parse(File("src/main/res/xml/locales_config.xml"))
        assertEquals(listOf("en", "zh-Hans", "ja"), children(config).map {
            it.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        })
    }

    private fun resources(source: String, folder: String): Map<String, Element> {
        val elements = children(parse(File("src/$source/res/$folder/strings.xml")))
        val byName = elements.associateBy { it.getAttribute("name") }
        assertEquals("Duplicate resource in $source/$folder", elements.size, byName.size)
        return byName
    }

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file).documentElement

    private fun children(element: Element): List<Element> =
        (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }

    private fun other(plural: Element): String = children(plural).single { it.getAttribute("quantity") == "other" }.textContent

    private fun arguments(text: String): List<String> = Regex("%(?:[0-9]+\\$)?[dsf]").findAll(text).map { it.value }.sorted().toList()
}
