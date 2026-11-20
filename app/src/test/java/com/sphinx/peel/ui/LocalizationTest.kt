package com.sphinx.peel.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.R
import com.sphinx.peel.ui.lock.LockoutText
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/// Spanish follows the device locale (docs/phases/52-localization-es.md): every translatable key of `values/` is in
/// `values-es/` with the same placeholders (and nothing extra), plurals have one + other, and a few strings render.
@RunWith(RobolectricTestRunner::class)
class LocalizationTest {
    private class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val untranslatable: Set<String>)

    private fun load(dir: String): Res {
        val file = listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = linkedMapOf<String, String>()
        val skip = mutableSetOf<String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            strings[e.getAttribute("name")] = e.textContent
            if (e.getAttribute("translatable") == "false") skip += e.getAttribute("name")
        }
        val plurals = linkedMapOf<String, Map<String, String>>()
        val pn = doc.getElementsByTagName("plurals")
        for (i in 0 until pn.length) {
            val e = pn.item(i) as Element
            val items = e.getElementsByTagName("item")
            plurals[e.getAttribute("name")] = (0 until items.length).associate {
                val item = items.item(it) as Element
                item.getAttribute("quantity") to item.textContent
            }
        }
        return Res(strings, plurals, skip)
    }

    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")
    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test fun `values-es has every translatable key of values, with the same placeholders`() {
        val en = load("values")
        val es = load("values-es")
        val wanted = en.strings.keys - en.untranslatable
        assertEquals("missing in values-es", emptySet<String>(), wanted - es.strings.keys)
        assertEquals("extra in values-es", emptySet<String>(), es.strings.keys - wanted)
        for (key in wanted) {
            assertEquals("placeholders of $key", placeholders(en.strings.getValue(key)), placeholders(es.strings.getValue(key)))
            assertTrue("$key is empty", es.strings.getValue(key).isNotBlank())
        }
        assertEquals(en.plurals.keys, es.plurals.keys)
        for ((name, items) in en.plurals) {
            val spanish = es.plurals.getValue(name)
            assertTrue("$name needs one and other", spanish.keys.containsAll(listOf("one", "other")) && items.keys.containsAll(listOf("one", "other")))
            for (q in listOf("one", "other")) {
                assertEquals("placeholders of $name/$q", placeholders(items.getValue(q)), placeholders(spanish.getValue(q)))
            }
        }
    }

    @Test fun `english is the default`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Welcome to Peel", context.getString(R.string.onboarding_welcome))
        assertEquals("Step 2 of 3", context.getString(R.string.onboarding_step_of, 2, 3))
        assertEquals("1 minute", LockoutText.describe(context, 60_000))
        assertEquals("30 seconds", LockoutText.describe(context, 30_000))
        assertEquals("2 hours", LockoutText.describe(context, 2 * 3_600_000L))
    }

    @Test @Config(qualifiers = "es") fun `spanish follows the device locale`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Te damos la bienvenida a Peel", context.getString(R.string.onboarding_welcome))
        assertEquals("Paso 2 de 3", context.getString(R.string.onboarding_step_of, 2, 3))
        assertEquals("1 minuto", LockoutText.describe(context, 60_000))
        assertEquals("5 minutos", LockoutText.describe(context, 300_000))
        assertEquals("Peel", context.getString(R.string.app_name))
    }

    @Test @Config(qualifiers = "es") fun `a known engine message follows the language, an unknown one is shown as it came`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Escribe un nombre.", ErrorText.text(context, "Enter a name.", R.string.err_could_not_save))
        assertEquals("the broker said no", ErrorText.text(context, "the broker said no", R.string.err_could_not_save))
        assertEquals("No se pudo guardar la cuenta.", ErrorText.text(context, null as String?, R.string.err_could_not_save))
    }

    @Test fun `every message ErrorText knows is the engine's own text`() {
        assertEquals(R.string.err_not_an_invite, ErrorText.resourceFor(com.sphinx.peel.engine.InviteProblem.NOT_AN_INVITE.message))
        assertEquals(R.string.err_not_confirmed, ErrorText.resourceFor(com.sphinx.peel.engine.SignupRules.NOT_CONFIRMED))
    }
}
