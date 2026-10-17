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
    fun `the composer stacks a typing indicator over a horizontal input row`() {
        val root = parse("activity_chat.xml")
        val composer = byId(root, "composer")
        assertNotNull("activity_chat.xml must have the composer", composer)
        assertEquals("vertical", attr(composer!!, "orientation"))

        val typing = byId(root, "typing_indicator")
        assertNotNull("composer must contain the typing indicator", typing)
        assertEquals("@string/typing_indicator", attr(typing!!, "text"))
        assertEquals("the indicator starts hidden", "gone", attr(typing, "visibility"))

        // The input row keeps the v2.46/v2.47 contract: horizontal, weighted
        // field between attach and send.
        val row = ancestorByTag(byId(root, "reply_input")!!, "LinearLayout")
        assertNotNull("reply_input must sit in a LinearLayout row", row)
        assertEquals("horizontal", attr(row!!, "orientation"))
        for (id in listOf("reply_input", "attach_button", "send_button")) {
            assertNotNull("the input row must contain $id", byId(row, id))
        }
    }

    @Test
    fun `the message field is capped at the send contract length`() {
        val input = byId(parse("activity_chat.xml"), "reply_input")!!
        // SendReconcile classifies a confirmed send by the server-trimmed
        // text; letting the field exceed the server cap would make long
        // messages look "unconfirmed" and get queued under a fresh key.
        assertEquals(
            "reply_input maxLength must track SendReconcile.MAX_REPLY_CHARS",
            SendReconcile.MAX_REPLY_CHARS.toString(),
            attr(input, "maxLength"),
        )
    }

    @Test
    fun `attachment previews keep their accessibility label`() {
        val strings = parseValues("strings.xml")
        for (name in listOf("typing_indicator", "attachment_desc")) {
            val node = descendants(strings).firstOrNull { it.getAttribute("name") == name }
            assertNotNull("strings.xml must define $name", node)
            assertTrue("$name must not be empty", node!!.textContent.isNotBlank())
        }
        for (layout in listOf("item_message_in.xml", "item_message_out.xml")) {
            val image = descendants(parse(layout)).firstOrNull { it.tagName == "ImageView" }
            assertNotNull("$layout must have an ImageView", image)
            assertEquals(
                "$layout: the attachment image must carry a screen-reader label",
                "@string/attachment_desc",
                attr(image!!, "contentDescription"),
            )
        }
    }

    private fun ancestorByTag(start: Element, tag: String): Element? {
        var node = start.parentNode as? Element
        while (node != null) {
            if (node.tagName == tag) return node
            node = node.parentNode as? Element
        }
        return null
    }

    private fun parseValues(name: String): Element {
        val f = File(File("src/main/res/values"), name)
        assertTrue("missing resource file: ${f.path}", f.isFile)
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(f)
        return doc.documentElement
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
