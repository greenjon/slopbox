/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/shortcuts.c - Copyright (C) 2011 Jonathan Gordon
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Rockbox's Shortcuts menu (apps/shortcuts.c): a list kept in
 * shortcuts.txt, in Rockbox's own format:
 *
 *     [shortcut]
 *     type: browse
 *     data: /Music/Album/
 *     name: My album
 *     icon: -1
 *     talkclip:
 */
class Shortcuts(context: Context) {
    enum class Type(val text: String) {
        SETTING("setting"), FILE("file"), DEBUGITEM("debug"), BROWSER("browse"), SETTING_APPLY("apply"),
        PLAYLISTMENU("playlist menu"), SEPARATOR("separator"), SHUTDOWN("shutdown"), REBOOT("reboot"),
        TIME("time"),
    }

    class Shortcut(
        val type: Type,
        val name: String = "",
        /** Path (browse, file, playlist menu, debug); for settings the cfg name. */
        val data: String = "",
        /** SETTING_APPLY: the value to apply, as .cfg text. */
        val value: String = "",
        /** TIME: minutes for the sleep timer, -1 to toggle it; null for "talk". */
        val sleepTimeout: Int? = -1,
        val icon: Int = -1,
        val talkClip: String = "",
    )

    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<Shortcut> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return parse(text)
    }

    fun save(list: List<Shortcut>) {
        runCatching { file.writeText(format(list)) }.onFailure { Log.w("Shortcuts", "save failed", it) }
    }

    /** Rockbox shortcuts_add(): append one. */
    fun add(sc: Shortcut) = save(load() + sc)

    fun remove(index: Int) = save(load().filterIndexed { i, _ -> i != index })

    companion object {
        const val FILE_NAME = "shortcuts.txt"
        private const val HDR = "[shortcut]"

        /** Rockbox settings_parseline(): "name: value", both trimmed. */
        private fun parseLine(line: String): Pair<String, String>? {
            val c = line.indexOf(':').takeIf { it > 0 } ?: return null
            return line.substring(0, c).trim() to line.substring(c + 1).trim()
        }

        /** Rockbox shortcuts_init() / readline_cb() and verify_shortcut(). */
        fun parse(text: String): List<Shortcut> {
            val out = mutableListOf<Shortcut>()
            var type: Type? = null
            var started = false
            var name = ""; var data = ""; var value = ""; var sleep: Int? = -1; var icon = -1; var talk = ""
            var bad = false
            fun finish() {
                if (!started || bad) return
                val t = type ?: return
                val ok = when (t) {
                    Type.BROWSER, Type.FILE, Type.PLAYLISTMENU -> data.isNotEmpty()
                    Type.SETTING, Type.SETTING_APPLY -> data.isNotEmpty()
                    else -> true
                }
                if (ok) out += Shortcut(t, name, data, value, sleep, icon, talk)
            }
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.equals(HDR, ignoreCase = true)) {
                    finish()
                    started = true; type = null; name = ""; data = ""; value = ""; sleep = -1; icon = -1; talk = ""; bad = false
                    continue
                }
                if (!started) continue
                val (k, v) = parseLine(line) ?: continue
                when (k.lowercase()) {
                    "type" -> if (type == null) type = Type.entries.firstOrNull { it.text == v }
                    "name" -> name = v.take(63)
                    "data" -> when (type) {
                        null -> bad = true
                        Type.BROWSER, Type.FILE, Type.DEBUGITEM, Type.PLAYLISTMENU -> data = v
                        Type.SETTING, Type.SETTING_APPLY -> {
                            val p = parseLine(v)
                            if (p != null) { data = p.first; value = p.second }
                            else { type = Type.SETTING; data = v }
                        }
                        Type.TIME -> when {
                            v.equals("talk", ignoreCase = true) -> sleep = null
                            v.startsWith("sleep", ignoreCase = true) ->
                                sleep = if (v.length > 5) v.substring(5).trim().toIntOrNull() ?: 0 else -1
                            else -> bad = true
                        }
                        else -> {}
                    }
                    "icon" -> icon = v.toIntOrNull() ?: -1
                    "talkclip" -> talk = v
                }
            }
            finish()
            return out
        }

        /** Rockbox shortcuts_ata_idle_callback(): the file text. */
        fun format(list: List<Shortcut>): String = buildString {
            for (sc in list) {
                append(HDR).append("\ntype: ").append(sc.type.text).append("\ndata: ")
                when (sc.type) {
                    Type.TIME -> if (sc.sleepTimeout == null) append("talk")
                        else { append("sleep"); if (sc.sleepTimeout >= 0) append(" ").append(sc.sleepTimeout) }
                    Type.SETTING_APPLY -> append(sc.data).append(": ").append(sc.value)
                    else -> append(sc.data)
                }
                append("\nname: ").append(sc.name).append("\nicon: ").append(sc.icon)
                append("\ntalkclip: ").append(sc.talkClip).append("\n\n")
            }
        }
    }
}
