package com.amethyst2213.operatorchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Guards the chat composer geometry.
 *
 * v2.46 shipped with `reply_input` declared `layout_width="0dp"` and no
 * `layout_weight`. In a horizontal LinearLayout that collapses the field to zero
 * width, so the operator could see the attach and send buttons but had nowhere
 * to type - the app was unusable and nothing in the build caught it.
 *
 * A plain XML check runs on the JVM in CI, so this can never regress unnoticed.
 */
class LayoutComposerTest {

    private val layoutDir = File("src/main/res/layout")

    private fun parse(name: String): Element {
        val f = File(layoutDir, name)
        assertTrue("missing layout: ${f.path}", f.isFile)
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(f)
        return doc.documentElement
    }

    /** All descendant elements, in document order. */
    private fun descendants(root: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            val kids = e.childNodes
            for (i in 0 until kids.length) {
                val n = kids.item(i)
                if (n is Element) { out.add(n); walk(n) }
            }
        }
        walk(root)
        return out
    }

    private fun byId(root: Element, id: String): Element? =
        descendants(root).firstOrNull { it.getAttribute("android:id") == "@+id/$id" }

    private fun attr(e: Element, name: String): String = e.getAttribute("android:$name")

    @Test
    fun `the message field can actually be typed in`() {
        val root = parse("activity_chat.xml")
        val input = byId(root, "reply_input")
        assertNotNull("activity_chat.xml must have the reply_input field", input)

        val width = attr(input!!, "layout_width")
        val weight = attr(input, "layout_weight")

        // The bug: a zero-width field with no weight is invisible in a row.
        assertEquals("reply_input must be width 0dp (weighted)", "0dp", width)
        assertTrue("reply_input must carry layout_weight, or it collapses to nothing",
            weight.isNotBlank() && weight != "0")
    }

    @Test
    fun `the composer is a horizontal row holding field plus a send button`() {
        val root = parse("activity_chat.xml")
        val composer = byId(root, "composer")
        assertNotNull("activity_chat.xml must have the composer row", composer)
        assertEquals("horizontal", attr(composer!!, "orientation"))

        for (id in listOf("reply_input", "attach_button", "send_button")) {
            assertNotNull("composer must contain $id", byId(root, id))
        }
    }

    @Test
    fun `no view is declared zero-sized without a weight`() {
        // The same mistake anywhere else in the app would be just as invisible.
        val offenders = ArrayList<String>()
        val files = layoutDir.listFiles { f -> f.name.endsWith(".xml") } ?: emptyArray()
        assertTrue("no layouts found under ${layoutDir.absolutePath}", files.isNotEmpty())

        for (f in files) {
            val root = parse(f.name)
            for (e in descendants(root)) {
                val id = e.getAttribute("android:id").removePrefix("@+id/").ifBlank { e.tagName }
                val weight = e.getAttribute("android:layout_weight")
                val w = e.getAttribute("android:layout_width")
                val h = e.getAttribute("android:layout_height")
                // Hidden stubs (visibility="gone") are exempt: they are placeholders.
                val gone = e.getAttribute("android:visibility") == "gone"
                if (gone) continue
                if (w == "0dp" && weight.isBlank()) {
                    offenders += "${f.name}: $id (0dp width, no weight)"
                }
                // 0dp height is legitimate inside CoordinatorLayout/ConstraintLayout,
                // where a behaviour or constraint supplies the size.
                val positioned = e.getAttribute("app:layout_behavior").isNotBlank() ||
                    e.getAttribute("app:layout_constraintTop_toTopOf").isNotBlank() ||
                    e.getAttribute("app:layout_constraintBottom_toBottomOf").isNotBlank() ||
                    e.getAttribute("app:layout_scrollFlags").isNotBlank()
                if (h == "0dp" && weight.isBlank() && !positioned) {
                    offenders += "${f.name}: $id (0dp height, no weight/behavior)"
                }
            }
        }

        assertTrue("views collapse to nothing: " + offenders.joinToString("; "), offenders.isEmpty())
    }
}
