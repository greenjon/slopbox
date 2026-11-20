package llm.slop.slopbox

import llm.slop.slopbox.Shortcuts.Type
import org.junit.Assert.assertEquals
import org.junit.Test

/** shortcuts.txt as Rockbox's apps/shortcuts.c reads and writes it. */
class ShortcutsTest {
    private val sample = """
        [shortcut]
        type: browse
        data: /Music/Album/
        name: My album

        [shortcut]
        type: apply
        data: shuffle: on
        icon: 3

        [shortcut]
        type: setting
        data: repeat

        [shortcut]
        type: time
        data: sleep 30

        [shortcut]
        type: time
        data: sleep

        [shortcut]
        type: separator
        name: ---

        [shortcut]
        type: browse

        [shortcut]
        type: bogus
        data: x
    """.trimIndent()

    @Test fun parse() {
        val l = Shortcuts.parse(sample)
        assertEquals(listOf(Type.BROWSER, Type.SETTING_APPLY, Type.SETTING, Type.TIME, Type.TIME, Type.SEPARATOR), l.map { it.type })
        assertEquals("My album", l[0].name)
        assertEquals("/Music/Album/", l[0].data)
        assertEquals("shuffle" to "on", l[1].data to l[1].value)
        assertEquals(3, l[1].icon)
        assertEquals(30, l[3].sleepTimeout)
        assertEquals(-1, l[4].sleepTimeout)
    }

    @Test fun roundTrip() {
        val l = Shortcuts.parse(sample)
        val text = Shortcuts.format(l)
        assert(text.startsWith("[shortcut]\ntype: browse\ndata: /Music/Album/\nname: My album\nicon: -1\ntalkclip: \n\n"))
        assert("type: apply\ndata: shuffle: on\n" in text)
        assert("type: time\ndata: sleep 30\n" in text)
        assertEquals(l.map { it.type to it.data }, Shortcuts.parse(text).map { it.type to it.data })
    }
}
