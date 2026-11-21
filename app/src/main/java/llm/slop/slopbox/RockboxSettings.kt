/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/settings.c - Copyright (C) 2002 by Stuart Martin
 *   apps/settings_list.c - Copyright (C) 2007 Jonathan Gordon
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * Rockbox's settings, as extracted from its own source by settings-gen/
 * (apps/settings_list.c and the Settings menus in apps/menus/), plus the
 * user's values, stored as a Rockbox-format config.cfg.
 */

/** Rockbox settings_list.h flags used here. */
object RbFlags {
    const val F_T_MASK = 0x7L
    const val F_T_BOOL = 3L
    const val F_T_CUSTOM = 6L
    const val F_RGB = 0x20L
    const val F_TABLE_SETTING = 0x2000L
    const val F_ALLOW_ARBITRARY_VALS = 0x4000L
    const val F_TIME_SETTING = 0x10000L
    const val F_THEMESETTING = 0x800000L
    const val F_EQSETTING = 0x2000000L
    const val F_SOUNDSETTING = 0x4000000L
    const val F_DEPRECATED = 0x40000000L
}

class RbSetting(
    val index: Int,
    val cfg: String?,
    val flags: Long,
    val title: String,
    /** "bool", "choice", "int", "table", "sound", "custom", ... */
    val kind: String,
    val defaultInt: Int,
    /** Default as .cfg text, for custom settings. */
    val defaultText: String?,
    /** Every selectable value with Rockbox's display text, in screen order. */
    val labels: List<Pair<Int, String>>,
    /** Symbolic .cfg values; the i'th maps to value i (or table value i). */
    val cfgVals: List<String>?,
    val tableValues: List<Int>?,
) {
    val isCustom get() = kind == "custom"
    /**
     * Rockbox is_setting_quickscreenable(): a titled bool, or an int/choice
     * setting with its value table, not F_BANFROMQS. (Also what may become a
     * settings shortcut.)
     */
    val quickscreenable get() = flags and 0x80000000L == 0L && labels.isNotEmpty() &&
        kind in setOf("bool", "choice", "int", "table", "sound")
    /** Kept as text in config.cfg: custom settings and Rockbox's string settings (F_T_CHARPTR/UCHARPTR). */
    val isText get() = isCustom || (flags and RbFlags.F_T_MASK).let { it == 4L || it == 5L }
    fun has(flag: Long) = flags and flag != 0L

    fun label(value: Int): String =
        labels.firstOrNull { it.first == value }?.second ?: value.toString()

    /** Rockbox cfg_to_string(). */
    fun toCfg(value: Int): String {
        // Rockbox cfg_to_string(): colours as rrggbb.
        if (flags and RbFlags.F_RGB != 0L) return "%06x".format(value and 0xFFFFFF)
        val vals = cfgVals
        if (vals != null) {
            val i = if (tableValues != null) tableValues.indexOf(value) else value
            vals.getOrNull(i)?.let { return it }
        }
        if (flags and RbFlags.F_T_MASK == RbFlags.F_T_BOOL) return if (value != 0) "on" else "off"
        return value.toString()
    }

    /** Rockbox cfg_string_to_int(), falling back to a number. */
    fun fromCfg(text: String): Int? {
        val t = text.trim()
        // Rockbox hex_to_rgb(): six hex digits.
        if (flags and RbFlags.F_RGB != 0L) return t.takeIf { it.length == 6 }?.toIntOrNull(16)
        cfgVals?.indexOf(t)?.takeIf { it >= 0 }?.let { i ->
            return tableValues?.getOrNull(i) ?: i
        }
        if (flags and RbFlags.F_T_MASK == RbFlags.F_T_BOOL) {
            return when (t.lowercase()) { "on", "yes", "true" -> 1; "off", "no", "false" -> 0; else -> null }
        }
        return t.toIntOrNull()
    }
}

class RbMenuItem(
    val type: Int,
    val text: String,
    val setting: Int,
    val function: String?,
    val param: String?,
    val items: List<RbMenuItem>,
    /** Rockbox's dynamic-text callback for function items (e.g. "wps_context_menu_get_name"). */
    val textFn: String? = null,
) {
    companion object {
        const val MT_MENU = 0
        const val MT_SETTING = 1
        const val MT_SETTING_W_TEXT = 2
        const val MT_FUNCTION_CALL = 3
        const val MT_FUNCTION_CALL_W_PARAM = 4
    }
    val isMenu get() = type == MT_MENU
    val isSetting get() = type == MT_SETTING || type == MT_SETTING_W_TEXT
}

class RbCatalog(
    val settings: List<RbSetting>,
    val menu: RbMenuItem,
    val lang: Map<String, String>,
    val rockboxRevision: String,
) {
    val byCfg: Map<String, RbSetting> = settings.filter { it.cfg != null }.associateBy { it.cfg!! }
    fun str(id: String): String = lang[id] ?: id

    companion object {
        fun load(context: Context): RbCatalog {
            val json = JSONObject(context.assets.open("rockbox_settings.json").bufferedReader().readText())
            val lang = json.getJSONObject("lang").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            val rockbox = json.getJSONArray("settings").objects().map { parseSetting(it) }
            val extra = SlopBoxSettings.ALL.mapIndexed { i, x -> x.setting(rockbox.size + i) }
            val settings = rockbox + extra
            val menu = SlopBoxSettings.addMenu(parseMenu(json.getJSONObject("menu"), settings, lang), lang, rockbox.size)
            return RbCatalog(settings, menu, lang, json.optString("rockbox_revision"))
        }

        private fun text(v: Any?): String? = when (v) {
            is JSONObject -> v.optString("text")
            is String -> v
            else -> null
        }

        /**
         * Defaults SlopBox changes from Rockbox's (each listed in DIFFERENCES.md).
         * Status Bar: Bottom, where the mini-player is in thumb reach (Rockbox: Top).
         * Colours: cabbiev2's (wps/WPSLIST), the theme Rockbox installs as its default.
         */
        private val SLOPBOX_DEFAULTS: Map<String, Any> = mapOf(
            "statusbar" to 2,
            "foreground color" to 0xCCCCCC,
            "background color" to 0x000000,
            "line selector start color" to 0xFDE89E,
            "line selector end color" to 0xB58C00,
            "line selector text color" to 0x000000,
            // WPSLIST "list separator height..+&touchscreen: auto"
            "list separator height" to -1,
        )

        private fun parseSetting(o: JSONObject): RbSetting {
            val kind = o.getString("kind")
            val sub = o.optJSONObject(kind)
            val labels = o.optJSONArray("labels")?.let { a ->
                (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getInt(0) to p.getString(1) } }
            } ?: when (kind) {
                "bool" -> listOf(
                    0 to (text(sub?.opt("no")) ?: "Off"),
                    1 to (text(sub?.opt("yes")) ?: "On"),
                )
                "choice" -> sub!!.getJSONArray("options").let { a ->
                    (0 until a.length()).map { it to (text(a.get(it)) ?: it.toString()) }
                }
                else -> emptyList()
            }
            val cfgVals = (sub?.optString("cfg_vals")?.takeIf { it.isNotEmpty() && it != "null" }
                ?: o.optString("cfg_vals").takeIf { it.isNotEmpty() && it != "null" })
                ?.split(',')
            val def = SLOPBOX_DEFAULTS[o.optString("cfg")] ?: o.opt("default")
            return RbSetting(
                index = o.getInt("index"),
                cfg = o.optString("cfg").takeIf { !o.isNull("cfg") },
                flags = o.getLong("flags"),
                title = text(o.opt("title")) ?: o.optString("cfg"),
                kind = kind,
                defaultInt = when (def) {
                    is Boolean -> if (def) 1 else 0
                    is Int -> def
                    else -> 0
                },
                defaultText = o.optString("default_cfg").takeIf { o.has("default_cfg") }
                    ?: (def as? String)?.takeIf { !it.startsWith("?") && kind != "custom" },
                labels = labels,
                cfgVals = cfgVals,
                tableValues = sub?.optJSONArray("values")?.let { a -> (0 until a.length()).map { a.getInt(it) } },
            )
        }

        private fun parseMenu(o: JSONObject, settings: List<RbSetting>, lang: Map<String, String>): RbMenuItem {
            val type = o.getInt("type")
            val setting = o.optInt("setting", -1)
            val text = text(o.opt("text"))
                ?: settings.getOrNull(setting)?.title
                ?: when (o.optString("text_fn")) {
                    "sleeptimer_getname" -> lang["LANG_SLEEP_TIMER"]
                    else -> null
                }
                ?: "?"
            val items = o.optJSONArray("items")?.objects()?.map { parseMenu(it, settings, lang) } ?: emptyList()
            return RbMenuItem(
                type = type,
                text = text,
                setting = setting,
                function = o.optString("function").takeIf { o.has("function") && !o.isNull("function") },
                param = o.optString("param").takeIf { o.has("param") },
                items = items,
                textFn = o.optString("text_fn").takeIf { o.has("text_fn") && !o.isNull("text_fn") },
            )
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    }
}

/** Rockbox EQ band ("eq filter N"): type 0 low shelf / 1 peak / 2 high shelf; Q, gain in tenths. */
data class EqBand(val type: Int, val cutoff: Int, val q: Int, val gain: Int) {
    /** Rockbox eq_write_to_cfg(). */
    fun toCfg() = "$cutoff, $q, $gain, ${TYPES[type]}"

    companion object {
        val TYPES = listOf("LOW_SHELF", "PEAK", "HIGH_SHELF")

        /** Rockbox eq_load_from_cfg() (new format). */
        fun parse(text: String): EqBand? {
            val p = text.split(',').map { it.trim() }
            if (p.size < 3) return null
            val type = TYPES.indexOf(p.getOrNull(3)?.uppercase()).takeIf { it >= 0 } ?: 1
            return EqBand(type, p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null,
                p[2].toIntOrNull() ?: return null)
        }
    }
}

/**
 * The user's settings: Rockbox values keyed by .cfg name, saved as
 * filesDir/config.cfg in Rockbox's format (changed settings only, like
 * Rockbox's own config.cfg), and pushed to the engine.
 */
class SettingsStore private constructor(private val context: Context, val catalog: RbCatalog) {
    private val ints = HashMap<String, Int>()
    private val texts = HashMap<String, String>()
    private val file = File(context.filesDir, "config.cfg")
    private val listeners = mutableListOf<() -> Unit>()

    init {
        if (file.exists()) loadCfg(file.readText(), save = false)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun int(s: RbSetting): Int = ints[s.cfg] ?: s.defaultInt
    fun text(s: RbSetting): String = texts[s.cfg] ?: s.defaultText ?: ""
    fun int(cfg: String): Int = catalog.byCfg[cfg]?.let { int(it) } ?: 0

    fun set(s: RbSetting, value: Int) {
        val cfg = s.cfg ?: return
        if (value == s.defaultInt) ints.remove(cfg) else ints[cfg] = value
        applyToEngine(s)
        changed()
    }

    fun setText(s: RbSetting, value: String) {
        val cfg = s.cfg ?: return
        if (value == s.defaultText) texts.remove(cfg) else texts[cfg] = value
        applyToEngine(s)
        changed()
    }

    /** Rockbox reset_setting(): back to the default. */
    fun reset(s: RbSetting) {
        if (s.isText) setText(s, s.defaultText ?: "") else set(s, s.defaultInt)
    }

    /** Rockbox cfg_to_string(): the value as .cfg text. */
    fun cfgText(s: RbSetting): String = if (s.isText) text(s) else s.toCfg(int(s))

    fun eqBand(band: Int): EqBand {
        val s = catalog.byCfg["eq filter $band"] ?: return DEFAULT_BANDS[band]
        return EqBand.parse(text(s)) ?: DEFAULT_BANDS[band]
    }

    fun setEqBand(band: Int, value: EqBand) {
        catalog.byCfg["eq filter $band"]?.let { setText(it, value.toCfg()) }
    }

    /** Rockbox reset_settings(): everything back to defaults. */
    fun resetAll() {
        ints.clear()
        texts.clear()
        applyAllToEngine()
        changed()
    }

    fun resetEq() {
        for (b in 0 until EQ_BANDS) catalog.byCfg["eq filter $b"]?.let { texts.remove(it.cfg) }
        applyAllToEngine()
        changed()
    }

    /** Rockbox settings_write_config(): [mode] is SAVE_ALL / SAVE_CHANGED / SAVE_SOUND / SAVE_THEME / SAVE_EQ. */
    fun toCfg(mode: Int): String {
        val sb = StringBuilder()
        sb.append("# .cfg file created by SlopBox (settings from Rockbox ${catalog.rockboxRevision})\r\n\r\n")
        for (s in catalog.settings) {
            val cfg = s.cfg ?: continue
            if (s.has(RbFlags.F_DEPRECATED)) continue
            when (mode) {
                SAVE_CHANGED -> if (cfg !in ints && cfg !in texts) continue
                SAVE_SOUND -> if (!s.has(RbFlags.F_SOUNDSETTING)) continue
                SAVE_THEME -> if (!s.has(RbFlags.F_THEMESETTING)) continue
                SAVE_EQ -> if (!s.has(RbFlags.F_EQSETTING)) continue
            }
            val value = if (s.isText) text(s) else s.toCfg(int(s))
            if (value.isEmpty()) continue
            sb.append(cfg).append(": ").append(value).append("\r\n")
        }
        return sb.toString()
    }

    /** Rockbox settings_load_config(): unknown names are ignored. Returns how many were applied. */
    fun loadCfg(text: String, save: Boolean = true): Int {
        var n = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            val s = catalog.byCfg[name] ?: continue
            if (s.isText) {
                if (value == s.defaultText) texts.remove(name) else texts[name] = value
            } else {
                val v = s.fromCfg(value) ?: continue
                if (v == s.defaultInt) ints.remove(name) else ints[name] = v
            }
            n++
        }
        // Old-style EQ band names map onto the new "eq filter N".
        OLD_EQ_NAMES.forEachIndexed { band, old ->
            texts.remove(old)?.let { v ->
                EqBand.parse("$v, ${EqBand.TYPES[if (band == 0) 0 else if (band == 9) 2 else 1]}")
                    ?.let { texts["eq filter $band"] = it.toCfg() }
            }
        }
        if (save) {
            applyAllToEngine()
            changed()
        }
        return n
    }

    private fun changed() {
        try {
            file.writeText(toCfg(SAVE_CHANGED))
        } catch (e: Exception) {
            Log.w("SlopBox", "can't save config.cfg", e)
        }
        listeners.toList().forEach { it() }
    }

    // ---- Engine ----------------------------------------------------------------

    /** Is this setting wired to anything in SlopBox yet? */
    fun isLive(s: RbSetting): Boolean =
        s.cfg in ENGINE_IDS || s.cfg in PLAYER_SETTINGS || s.cfg?.startsWith("eq filter ") == true

    fun applyAllToEngine() {
        Engine.prepare(context)
        catalog.byCfg["default codepage"]?.let { Engine.setCodepage(int(it)) }
        for (s in catalog.settings) applyToEngine(s)
    }

    private fun applyToEngine(s: RbSetting) {
        val cfg = s.cfg ?: return
        if (cfg == "default codepage") { Engine.prepare(context); Engine.setCodepage(int(s)); return }
        ENGINE_IDS[cfg]?.let { id ->
            val v = if (s.isCustom) text(s).trim().toIntOrNull() ?: 0 else int(s)
            Engine.setSetting(id, v)
            return
        }
        if (cfg.startsWith("eq filter ")) {
            val band = cfg.removePrefix("eq filter ").toIntOrNull() ?: return
            val b = eqBand(band)
            Engine.setEqBand(band, b.type, b.cutoff, b.q, b.gain)
        }
    }

    companion object {
        const val SAVE_CHANGED = 0
        const val SAVE_ALL = 1
        const val SAVE_THEME = 2
        const val SAVE_SOUND = 3
        const val SAVE_EQ = 100
        const val EQ_BANDS = 10

        /** Rockbox eq_defaults. */
        val DEFAULT_BANDS = listOf(
            EqBand(0, 32, 7, 0), EqBand(1, 64, 10, 0), EqBand(1, 125, 10, 0), EqBand(1, 250, 10, 0),
            EqBand(1, 500, 10, 0), EqBand(1, 1000, 10, 0), EqBand(1, 2000, 10, 0), EqBand(1, 4000, 10, 0),
            EqBand(1, 8000, 10, 0), EqBand(2, 16000, 7, 0),
        )
        private val OLD_EQ_NAMES = listOf("eq low shelf filter") +
            (1..8).map { "eq peak filter $it" } + "eq high shelf filter"

        /** .cfg name -> engine/sb_settings.h id. Keep in step with enum sb_setting_id. */
        val ENGINE_IDS = listOf(
            "volume", "volume limit", "bass", "treble", "balance", "channels", "stereo_width",
            "crossfeed", "crossfeed direct gain", "crossfeed cross gain", "crossfeed hf attenuation",
            "crossfeed hf cutoff", "eq enabled", "eq precut", "dithering enabled", "surround enabled",
            "surround balance", "surround_fx1", "surround_fx2", "side only", "surround mix", "pbe",
            "pbe precut", "afr enabled", "timestretch enabled", "compressor threshold",
            "compressor makeup gain", "compressor ratio", "compressor knee", "compressor attack time",
            "compressor release time", "replaygain type", "replaygain noclip", "replaygain preamp",
            "crossfade", "crossfade fade in delay", "crossfade fade in duration",
            "crossfade fade out delay", "crossfade fade out duration", "crossfade fade out mode",
        ).withIndex().associate { (i, name) -> name to i }

        /** Playback Settings that SlopPlayer acts on. */
        val PLAYER_SETTINGS = setOf(
            "shuffle", "repeat", "play selected", "volume fade", "skip length",
            "prevent track skip", "rewind across tracks", "rewind duration on pause",
            "single mode", "party mode", "beep", "folder navigation", "constrain next folder",
            "resume rewind", "play log", "album art", "scan min step", "seek acceleration",
            "autoresume enable", "autoresume next track", "start in screen",
            "idle poweroff", "sleeptimer duration", "sleeptimer on startup",
            "keypress restarts sleeptimer", "show shutdown message",
            "autocreate bookmarks", "autoupdate bookmarks", "autoload bookmarks",
            "use most-recent-bookmarks", "cuesheet support",
            "sort playlists", "playlist viewer icons", "playlist viewer indices",
            "playlist viewer track display", "recursive directory insert",
            "warn when erasing dynamic playlist", "keep current track when replacing playlist",
            "show shuffled adding options", "show queue options",
            "context_wps", "Semitone pitch change", "Timestretch mode",
            "start directory", "playlist catalog directory", "gather runtime data",
            "qs top", "qs left", "qs right", "qs bottom", "shortcuts instead of quickscreen",
            "autoresume next track paths", "default codepage", "tagcache_autoupdate", "max files in dir", "max files in playlist", "root menu order", "sort case", "sort dirs", "sort files", "sort interpret number", "show files",
            "show filename exts", "follow playlist", "show path in browser", "keep directory", "show icons", "statusbar",
            "foreground color", "background color", "line selector start color", "line selector end color",
            "line selector text color", "list separator color", "list separator height", "selector type",
            "scrollbar", "scrollbar width",
            SlopBoxSettings.SWIPE_TRACKS, SlopBoxSettings.SWIPE_PLAYLIST, SlopBoxSettings.DOUBLE_TAP,
            SlopBoxSettings.LONG_PRESS, SlopBoxSettings.EDGE_VOLUME, SlopBoxSettings.HAPTICS,
            SlopBoxSettings.ART_ACCENT, "wps", SlopBoxSettings.FLAT, SlopBoxSettings.TEXT_SIZE, SlopBoxSettings.BOTTOM_NAV,
        )

        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context.applicationContext, RbCatalog.load(context.applicationContext))
                .also { instance = it; it.applyAllToEngine() }
        }
    }
}

/**
 * Settings SlopBox has and Rockbox doesn't, in Theme Settings › SlopBox.
 * They live in config.cfg under "slopbox ..." names, which Rockbox ignores.
 */
object SlopBoxSettings {
    sealed class Extra(val cfg: String, val title: String) { abstract fun setting(index: Int): RbSetting }
    class Bool(cfg: String, title: String, val default: Boolean) : Extra(cfg, title) {
        override fun setting(index: Int) = RbSetting(index, cfg, RbFlags.F_T_BOOL, title, "bool", if (default) 1 else 0,
            null, listOf(0 to "Off", 1 to "On"), listOf("off", "on"), null)
    }
    /** A choice: [labels] shown, [cfgVals] written to config.cfg. */
    class Choice(cfg: String, title: String, val labels: List<String>, val cfgVals: List<String>, val default: Int) : Extra(cfg, title) {
        override fun setting(index: Int) = RbSetting(index, cfg, 1L, title, "choice", default,
            null, labels.mapIndexed { i, l -> i to l }, cfgVals, null)
    }

    const val SWIPE_TRACKS = "slopbox swipe tracks"
    const val SWIPE_PLAYLIST = "slopbox swipe playlist"
    const val DOUBLE_TAP = "slopbox double tap play"
    const val LONG_PRESS = "slopbox long press menu"
    const val EDGE_VOLUME = "slopbox edge volume"
    const val HAPTICS = "slopbox haptics"
    const val ART_ACCENT = "slopbox art colours"
    const val FLAT = "slopbox flat style"
    const val TEXT_SIZE = "slopbox text size"
    const val BOTTOM_NAV = "slopbox navigation at bottom"

    val ALL: List<Extra> = listOf(
        Bool(SWIPE_TRACKS, "Swipe Art to Change Track", true),
        Bool(SWIPE_PLAYLIST, "Swipe Up for Playlist", true),
        Bool(DOUBLE_TAP, "Double-Tap to Play/Pause", true),
        Bool(LONG_PRESS, "Long-Press for Context Menu", true),
        Bool(EDGE_VOLUME, "Right Edge Volume", true),
        Bool(HAPTICS, "Haptic Feedback", true),
        Bool(ART_ACCENT, "Album Art Colours on While Playing Screen", false),
        Bool(FLAT, "Flat Style", false),
        Choice(TEXT_SIZE, "Text Size", listOf("Small", "Normal", "Large", "Huge"), listOf("small", "normal", "large", "huge"), 1),
        Bool(BOTTOM_NAV, "Navigation at Bottom", false),
    )

    /** Text Size as a scale for type and line heights. */
    val TEXT_SCALES = listOf(0.85f, 1f, 1.2f, 1.45f)
    /** How many of [ALL] are Now Playing gestures (the rest sit directly in the SlopBox menu). */
    private const val GESTURES = 5

    /** Theme Settings gets a SlopBox menu holding a Now Playing Gestures menu. */
    fun addMenu(root: RbMenuItem, lang: Map<String, String>, first: Int): RbMenuItem {
        val theme = lang["LANG_THEME_MENU"] ?: "Theme Settings"
        fun item(i: Int) = RbMenuItem(RbMenuItem.MT_SETTING, ALL[i].title, first + i, null, null, emptyList())
        val gestures = RbMenuItem(RbMenuItem.MT_MENU, "Now Playing Gestures", -1, null, null,
            (0 until GESTURES).map(::item))
        val slopbox = RbMenuItem(RbMenuItem.MT_MENU, "SlopBox", -1, null, null,
            listOf(gestures) + (GESTURES until ALL.size).map(::item))
        return RbMenuItem(root.type, root.text, root.setting, root.function, root.param,
            root.items.map { m ->
                if (m.isMenu && m.text == theme) RbMenuItem(m.type, m.text, m.setting, m.function, m.param, m.items + slopbox, m.textFn)
                else m
            }, root.textFn)
    }
}
