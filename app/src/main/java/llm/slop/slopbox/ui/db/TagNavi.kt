/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/tagtree.c - Copyright (C) 2005 by Miika Pekkarinen
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

/**
 * Rockbox's database menus, read from its own tagnavi.config (apps/tagnavi.config
 * in the Rockbox tree, shipped as an asset) and run over [TagDb].
 *
 * Supported: %format (with conditions, %sort, %limit, %strip), %menu_start,
 * %byfirstletter, %root_menu, %include, menu entries with ->, ==> and ~>,
 * and every clause operator Rockbox's tagtree.c knows. Conditions are OR'd
 * groups of AND'd clauses, as in tagcache.c. A clause value of "" asks the
 * user; "#tag#" means that tag of the track now playing.
 */
class TagNavi private constructor() {
    class Clause(val tag: String, val op: String, val value: String) {
        val asksUser: Boolean get() = value.isEmpty()
    }
    class Cond(val groups: List<List<Clause>>) {
        val clauses: List<Clause> get() = groups.flatten()
    }
    class Step(val tag: String, val fmt: String?, val cond: Cond?)
    sealed class Entry(val label: String) {
        class Sub(label: String, val menuId: String) : Entry(label)
        class Chain(label: String, val steps: List<Step>, val shuffle: Boolean) : Entry(label)
    }
    class Menu(val id: String, var title: String) {
        val entries = mutableListOf<Entry>()
    }
    class Format(
        val pattern: String, val tags: List<String>, val cond: Cond?,
        val inverse: Boolean, val limit: Int, val strip: Int,
    )

    val menus = LinkedHashMap<String, Menu>()
    val formats = HashMap<String, MutableList<Format>>()
    var root: String? = null
        private set
    private var current: Menu? = null

    fun menu(id: String): Menu? = menus[id.lowercase()]

    companion object {
        /** [include] returns the text of an %include'd file, or null if there is none. */
        fun parse(text: String, include: (String) -> String?): TagNavi {
            val n = TagNavi()
            n.parseText(text, include, depth = 0)
            return n
        }

        val NUMERIC = setOf(
            "year", "discnum", "tracknum", "length", "bitrate", "playcount", "rating", "lastplayed",
            "commitid", "entryage", "autoscore", "lm", "ls", "pm", "ps", "lastoffset", "lastelapsed",
        )
    }

    private fun parseText(text: String, include: (String) -> String?, depth: Int) {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            runCatching { parseLine(line, include, depth) }
        }
    }

    // ---- Tokens ----------------------------------------------------------

    private sealed interface Tok
    private data class Q(val s: String) : Tok
    private data class W(val s: String) : Tok
    private data class Op(val s: String) : Tok

    private val ops = listOf(
        "==>", "->", "~>", "==", "!=", "<=", ">=", "!~", "!^", "!$", "!@", "@~", "@^", "@$",
        "*~", "*^", "*$", "=", "<", ">", "~", "^", "$", "@", "?", "&", "|",
    )

    private fun tokens(line: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch.isWhitespace() -> i++
                ch == '"' -> {
                    val end = line.indexOf('"', i + 1).let { if (it < 0) line.length else it }
                    out += Q(line.substring(i + 1, end))
                    i = end + 1
                }
                ch.isLetterOrDigit() || ch == '_' || ch == '%' || ch == '#' -> {
                    val start = i
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] in "_%#.")) i++
                    out += W(line.substring(start, i))
                }
                else -> {
                    val op = ops.firstOrNull { line.startsWith(it, i) } ?: error("bad char $ch")
                    out += Op(op)
                    i += op.length
                }
            }
        }
        return out
    }

    private class Cursor(val t: List<Tok>) {
        var i = 0
        fun peek(): Tok? = t.getOrNull(i)
        fun next(): Tok? = t.getOrNull(i++)
        fun isOp(s: String) = (peek() as? Op)?.s == s
    }

    private fun Cursor.str(): String = (next() as? Q)?.s ?: error("string expected")
    private fun Cursor.word(): String = (next() as? W)?.s ?: error("word expected")

    // ---- Lines -----------------------------------------------------------

    private fun parseLine(line: String, include: (String) -> String?, depth: Int) {
        val c = Cursor(tokens(line))
        when (val first = c.peek()) {
            is W -> {
                c.next()
                when (first.s.lowercase()) {
                    "%format" -> {
                        val name = c.str()
                        val pattern = c.str()
                        val tags = mutableListOf<String>()
                        while (c.peek() is W && !(c.peek() as W).s.startsWith("%")) tags += c.word().lowercase()
                        val cond = if (c.isOp("?")) { c.next(); cond(c) } else null
                        var inverse = false
                        var limit = 0
                        var strip = 0
                        while (c.peek() is W) {
                            val opt = c.word().lowercase()
                            if (c.isOp("=")) c.next()
                            val v = c.str()
                            when (opt) {
                                "%sort" -> inverse = v == "inverse"
                                "%limit" -> limit = v.toIntOrNull() ?: 0
                                "%strip" -> strip = v.toIntOrNull() ?: 0
                            }
                        }
                        formats.getOrPut(name) { mutableListOf() } += Format(pattern, tags, cond, inverse, limit, strip)
                    }
                    "%include" -> {
                        val path = c.str()
                        if (depth < 4) include(path)?.let { parseText(it, include, depth + 1) }
                    }
                    "%menu_start" -> startMenu(c.str(), c.str())
                    "%byfirstletter" -> {
                        val id = c.str()
                        val title = c.str()
                        val tag = c.str()
                        startMenu(id, title)
                        firstLetterLines(tag).forEach { parseLine(it, include, depth) }
                        current = null
                    }
                    "%root_menu" -> { if (root == null) root = c.str().lowercase() }
                }
            }
            is Q -> {
                val menu = current ?: return
                val label = c.str()
                when ((c.next() as? Op)?.s) {
                    "==>" -> menu.entries += Entry.Sub(label, c.str().lowercase())
                    "->" -> menu.entries += Entry.Chain(label, steps(c), shuffle = false)
                    "~>" -> menu.entries += Entry.Chain(label, steps(c), shuffle = true)
                }
            }
            else -> {}
        }
    }

    private fun startMenu(id: String, title: String) {
        val m = menus.getOrPut(id.lowercase()) { Menu(id.lowercase(), title) }
        m.title = title
        current = m
    }

    /** What tagtree.c's build_firstletter_menu() generates. */
    private fun firstLetterLines(tag: String): List<String> {
        val sub = if ("artist" in tag) "album -> title" else "title"
        return buildList {
            add("\"Numeric\" -> $tag ? $tag > \"/\" & $tag < \":\" -> $sub = \"fmt_title\"")
            for (ch in 'A'..'Z') add("\"$ch\" -> $tag ? $tag ^ \"$ch\" -> $sub = \"fmt_title\"")
            add("\"Special character\" -> $tag ? $tag *^ \"a|b|c|d|e|f|g|h|i|j|k|l|m|n|o|p|q|r|s|t|u|v|w|x|y|z|0|1|2|3|4|5|6|7|8|9\" -> $sub = \"fmt_title\"")
        }
    }

    private fun steps(c: Cursor): List<Step> {
        val out = mutableListOf<Step>()
        while (true) {
            val tag = c.word().lowercase()
            var fmt: String? = null
            var cond: Cond? = null
            while (c.isOp("=") || c.isOp("?")) {
                if ((c.next() as Op).s == "=") fmt = c.str() else cond = cond(c)
            }
            out += Step(tag, fmt, cond)
            if (!c.isOp("->")) break
            c.next()
        }
        return out
    }

    private fun cond(c: Cursor): Cond {
        val groups = mutableListOf<MutableList<Clause>>(mutableListOf())
        while (true) {
            val p = c.peek()
            if (p !is W || p.s.startsWith("%")) break
            val tag = c.word().lowercase()
            val op = (c.next() as? Op)?.s ?: error("operator expected")
            groups.last() += Clause(tag, op, c.str())
            when {
                c.isOp("&") -> c.next()
                c.isOp("|") -> { c.next(); groups.add(mutableListOf()) }
                else -> break
            }
        }
        return Cond(groups.filter { it.isNotEmpty() })
    }
}

/** Natural, case-insensitive order ("Track 2" before "Track 10"), like Rockbox's "interpret numbers". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
