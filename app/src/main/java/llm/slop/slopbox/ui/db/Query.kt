/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/tagtree.c - Copyright (C) 2005 by Miika Pekkarinen
 *   apps/tagcache.c - Copyright (C) 2005 by Miika Pekkarinen
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox.ui.db

import llm.slop.slopbox.ui.db.TagNavi.Clause
import llm.slop.slopbox.ui.db.TagNavi.Cond
import llm.slop.slopbox.ui.db.TagNavi.Entry
import llm.slop.slopbox.ui.db.TagNavi.Format

const val UNTAGGED = "[Untagged]"

/** Tag values of a track, by tagnavi name, runtime statistics included. */
class Tags(private val commits: Int, private val stats: (String) -> Stats = { TagDb.statsOf(it) }) {
    fun str(t: Track, tag: String): String = when (tag) {
        "title" -> t.title
        "artist" -> t.artist
        "album" -> t.album
        "albumartist", "ensemble" -> t.albumArtist
        "canonicalartist" -> t.artist.ifEmpty { t.albumArtist }
        "genre" -> t.genre
        "composer" -> t.composer
        "filename" -> t.path
        "basename" -> t.name
        in TagNavi.NUMERIC -> num(t, tag).toString()
        else -> ""
    }

    fun num(t: Track, tag: String): Long = when (tag) {
        "year" -> t.year.toLong()
        "tracknum" -> t.trackNum.toLong()
        "discnum" -> t.discNum.toLong()
        "length" -> t.lengthMs
        "lm" -> t.lengthMs / 60000
        "ls" -> (t.lengthMs / 1000) % 60
        "bitrate" -> t.bitrate.toLong()
        "commitid" -> t.commitId.toLong()
        "entryage" -> (commits - t.commitId).toLong()
        "playcount" -> stats(t.id).playcount.toLong()
        "rating" -> stats(t.id).rating.toLong()
        "playtime" -> stats(t.id).playtime
        "pm" -> stats(t.id).playtime / 60000
        "ps" -> (stats(t.id).playtime / 1000) % 60
        "lastplayed" -> stats(t.id).lastplayed
        "autoscore" -> autoscore(t)
        in TagNavi.NUMERIC -> 0
        else -> str(t, tag).takeWhile { it.isDigit() }.toLongOrNull() ?: 0
    }

    /** Rockbox tag_virt_autoscore: 100 x playtime / length / playcount, in integers. */
    private fun autoscore(t: Track): Long {
        val st = stats(t.id)
        val length = t.lengthMs
        if (length == 0L || st.playcount == 0) return 0
        val playtime = st.playtime
        return (100 * (playtime / length) + (100 * (playtime % length)) / length) / st.playcount
    }

    /** A value as listed: empty shows as [Untagged]. */
    fun shown(t: Track, tag: String): String = str(t, tag).ifEmpty { UNTAGGED }
}

/**
 * One open database list: an entry's chain of steps, the step shown,
 * the values picked at earlier steps (null: no constraint, after [All tracks]),
 * and what the user typed for clauses with "".
 */
class Level(
    val entry: Entry.Chain,
    val step: Int,
    val picks: List<String?>,
    val inputs: Map<Clause, String>,
    val title: String,
) {
    val isTrackList: Boolean get() = step == entry.steps.lastIndex || entry.steps[step].tag in TRACK_TAGS

    companion object {
        val TRACK_TAGS = setOf("title", "filename", "basename")
    }
}

/** Runs [TagNavi] chains over the database. */
class Query(
    private val navi: TagNavi, private val tracks: List<Track>, commits: Int, private val playing: Track?,
    stats: (String) -> Stats = { TagDb.statsOf(it) },
) {
    val tags = Tags(commits, stats)

    private fun resolve(c: Clause, inputs: Map<Clause, String>): String {
        if (c.value.isEmpty()) return inputs[c] ?: ""
        val v = c.value
        if (v.length > 2 && v.startsWith("#") && v.endsWith("#")) {
            val tag = v.substring(1, v.length - 1).lowercase()
            val p = playing ?: return "\u0000" // nothing playing: matches nothing
            return if (tag == "directory") p.path.substringBeforeLast('/') + "/" else tags.str(p, tag)
        }
        return v
    }

    private fun clause(t: Track, c: Clause, inputs: Map<Clause, String>): Boolean {
        val v = resolve(c, inputs)
        if (c.tag in TagNavi.NUMERIC && c.op in setOf("=", "==", "!=", ">", ">=", "<", "<=")) {
            val n = tags.num(t, c.tag)
            val w = v.trim().toLongOrNull() ?: 0
            return when (c.op) {
                "=", "==" -> n == w
                "!=" -> n != w
                ">" -> n > w
                ">=" -> n >= w
                "<" -> n < w
                else -> n <= w
            }
        }
        val s = tags.str(t, c.tag).ifEmpty { UNTAGGED }
        val any = v.split('|')
        return when (c.op) {
            "=", "==" -> s.equals(v, true)
            "!=" -> !s.equals(v, true)
            ">" -> s.compareTo(v, true) > 0
            ">=" -> s.compareTo(v, true) >= 0
            "<" -> s.compareTo(v, true) < 0
            "<=" -> s.compareTo(v, true) <= 0
            "~" -> s.contains(v, true)
            "!~" -> !s.contains(v, true)
            "^" -> s.startsWith(v, true)
            "!^" -> !s.startsWith(v, true)
            "$" -> s.endsWith(v, true)
            "!$" -> !s.endsWith(v, true)
            "@" -> any.any { s.equals(it, true) }
            "!@" -> any.none { s.equals(it, true) }
            "@~" -> any.any { s.contains(it, true) }
            "@^" -> any.any { s.startsWith(it, true) }
            "@$" -> any.any { s.endsWith(it, true) }
            "*~" -> any.none { s.contains(it, true) }
            "*^" -> any.none { s.startsWith(it, true) }
            "*$" -> any.none { s.endsWith(it, true) }
            else -> false
        }
    }

    private fun cond(t: Track, c: Cond?, inputs: Map<Clause, String>): Boolean =
        c == null || c.groups.isEmpty() || c.groups.any { g -> g.all { clause(t, it, inputs) } }

    /** Tracks that pass every step up to [level]'s, with the picks made so far. */
    fun matching(level: Level): List<Track> {
        val steps = level.entry.steps
        return tracks.filter { t ->
            (0..level.step).all { i ->
                val st = steps[i]
                cond(t, st.cond, level.inputs) &&
                    (i >= level.step || level.picks.getOrNull(i)?.let { tags.shown(t, st.tag).equals(it, true) } ?: true)
            }
        }
    }

    /** Distinct values at a non-final step, sorted. */
    fun values(level: Level): List<String> {
        val tag = level.entry.steps[level.step].tag
        val seen = LinkedHashMap<String, String>()
        for (t in matching(level)) {
            val v = tags.shown(t, tag)
            seen.putIfAbsent(v.lowercase(), v)
        }
        // tagcache.c sorts <Untagged> before everything else.
        return seen.values.sortedWith(compareBy<String> { it != UNTAGGED }.then(NaturalOrder))
    }

    /** Lines of a track list: (label, track), formatted and sorted as the step's %format says. */
    fun trackLines(level: Level): List<Pair<String, Track>> {
        val step = level.entry.steps[level.step]
        val list = matching(level)
        // A track list before the last step ("A" -> title ? title ^ "A" -> title = "fmt_title")
        // borrows the format given further on.
        val fmtName = step.fmt ?: level.entry.steps.drop(level.step).firstNotNullOfOrNull { it.fmt }
        val fmts = fmtName?.let { navi.formats[it] }
        if (fmts.isNullOrEmpty()) {
            return list.map { tags.shown(it, step.tag) to it }.sortedWith(compareBy(NaturalOrder) { it.first })
        }
        var opts: Format? = null
        val lines = list.map { t ->
            val f = fmts.firstOrNull { cond(t, it.cond, level.inputs) } ?: fmts.last()
            if (opts == null) opts = f
            format(t, f) to t
        }
        val o = opts ?: return lines
        var sorted = lines.sortedWith(compareBy(NaturalOrder) { it.first })
        if (o.inverse) sorted = sorted.reversed()
        if (o.limit > 0) sorted = sorted.take(o.limit)
        if (o.strip > 0) sorted = sorted.map { (s, t) -> s.drop(o.strip) to t }
        return sorted
    }

    /** All tracks under a level, in the order its final list would show them ([All tracks]). */
    fun allTracks(level: Level): List<Track> {
        val last = level.entry.steps.lastIndex
        val picks = level.picks + List((last - level.picks.size).coerceAtLeast(0)) { null }
        return trackLines(Level(level.entry, last, picks, level.inputs, level.title)).map { it.second }
    }

    /** printf-ish, as tagtree.c's format_str(): %s, %d, %02d, %3d... one tag per conversion. */
    fun format(t: Track, f: Format): String {
        val sb = StringBuilder()
        val p = f.pattern
        var arg = 0
        var i = 0
        while (i < p.length) {
            val ch = p[i]
            if (ch != '%' || i + 1 >= p.length) { sb.append(ch); i++; continue }
            i++
            if (p[i] == '%') { sb.append('%'); i++; continue }
            var zero = false
            var left = false
            while (i < p.length && (p[i] == '0' || p[i] == '-')) {
                if (p[i] == '0') zero = true else left = true
                i++
            }
            var width = 0
            while (i < p.length && p[i].isDigit()) { width = width * 10 + (p[i] - '0'); i++ }
            val conv = p.getOrNull(i) ?: break
            i++
            val tag = f.tags.getOrNull(arg++) ?: ""
            val body = if (conv == 'd') tags.num(t, tag).toString() else tags.str(t, tag)
            val pad = (width - body.length).coerceAtLeast(0)
            when {
                left -> sb.append(body).append(" ".repeat(pad))
                zero && conv == 'd' -> sb.append("0".repeat(pad)).append(body)
                else -> sb.append(" ".repeat(pad)).append(body)
            }
        }
        return sb.toString()
    }
}
