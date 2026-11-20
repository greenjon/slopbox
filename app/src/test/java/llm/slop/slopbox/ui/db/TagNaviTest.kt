package llm.slop.slopbox.ui.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs Rockbox's real tagnavi.config over a few fake tracks. */
class TagNaviTest {
    private val navi = TagNavi.parse(
        File("../third_party/rockbox/apps/tagnavi.config").readText(),
    ) { null }

    private fun t(id: String, title: String, artist: String, album: String, n: Int, year: Int = 2000,
                  albumArtist: String = "", genre: String = "", commit: Int = 1) =
        Track(id, "d", "dir", "/$artist/$album/$id.flac", title, artist, album, albumArtist, genre, "",
            year, n, 0, 125_000, 320, 0, 0, commit)

    private val tracks = listOf(
        t("a1", "Second", "Beta", "Bee Album", 2),
        t("a2", "First", "Beta", "Bee Album", 1),
        t("a3", "Track 10", "alpha", "Alpha One", 10, year = 1999),
        t("a4", "Track 9", "alpha", "Alpha One", 9, year = 1999),
        t("a5", "", "", "", 0, year = 0),
        t("a6", "Only AA", "", "Comp", 1, albumArtist = "Various", commit = 3),
    )
    private val q = Query(navi, tracks, commits = 3, playing = tracks[0])

    private fun entry(menu: String, label: String) =
        navi.menu(menu)!!.entries.first { it.label == label } as TagNavi.Entry.Chain

    @Test fun mainMenu() {
        assertEquals("main", navi.root)
        val labels = navi.menu("main")!!.entries.map { it.label }
        assertTrue(labels.containsAll(listOf("Artist", "Album", "Genre", "Year", "First Letter", "Search", "Shuffle Songs")))
        assertEquals(28, navi.menu("custom_artist")!!.entries.size) // Numeric, A-Z, Special
    }

    @Test fun artistAlbumTitle() {
        val e = entry("main", "Artist")
        val l0 = Level(e, 0, emptyList(), emptyMap(), "Artist")
        // canonicalartist falls back to album artist; empty shows as [Untagged].
        assertEquals(listOf("[Untagged]", "alpha", "Beta", "Various"), q.values(l0))
        val l1 = Level(e, 1, listOf("alpha"), emptyMap(), "alpha")
        assertEquals(listOf("Alpha One"), q.values(l1))
        val l2 = Level(e, 2, listOf("alpha", "Alpha One"), emptyMap(), "Alpha One")
        // fmt_title: "%02d. %s - %02d:%02d" tracknum title Lm Ls, natural sort.
        assertEquals(listOf("09. Track 9 - 02:05", "10. Track 10 - 02:05"), q.trackLines(l2).map { it.first })
    }

    @Test fun untaggedUsesFileName() {
        val e = entry("main", "Album")
        val l = Level(e, 1, listOf("[Untagged]"), emptyMap(), "x")
        assertEquals(listOf("a5.flac - 02:05 (///a5.flac)"), q.trackLines(l).map { it.first })
    }

    @Test fun allTracksAndYearCondition() {
        val e = entry("main", "Year")
        val l0 = Level(e, 0, emptyList(), emptyMap(), "Year")
        assertEquals(listOf("1999", "2000"), q.values(l0)) // year > "0" drops the untagged one
        assertEquals(3, q.allTracks(Level(e, 1, listOf("2000"), emptyMap(), "")).size)
    }

    @Test fun searchAsksAndMatches() {
        val e = entry("search", "Album")
        val ask = e.steps.flatMap { it.cond?.clauses.orEmpty() }.single { it.asksUser }
        val l = Level(e, 0, emptyList(), mapOf(ask to "alpha"), "")
        assertEquals(listOf("Alpha One"), q.values(l))
    }

    @Test fun firstLetterAndRecent() {
        val b = navi.menu("custom_artist")!!.entries.first { it.label == "B" } as TagNavi.Entry.Chain
        assertEquals(listOf("Beta"), q.values(Level(b, 0, emptyList(), emptyMap(), "")))
        val recent = entry("main", "Recently Added")
        assertEquals(4, q.values(Level(recent, 0, emptyList(), emptyMap(), "")).size) // every album is < 4 scans old
        val same = navi.menu("same")!!.entries.first { it.label == "Album" } as TagNavi.Entry.Chain
        assertEquals(2, q.trackLines(Level(same, 0, emptyList(), emptyMap(), "")).size)
    }
}
