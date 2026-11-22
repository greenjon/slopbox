/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox.ui

import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.SlopBoxSettings
import llm.slop.slopbox.ui.db.TagDb

/**
 * SlopBox: Playback Settings › Minimum Stars and Play Unrated Tracks. Next,
 * Previous and the automatic track change pass over queue entries rated below
 * the minimum (and unrated ones, with Play Unrated off). The queue itself is
 * left alone, and a track picked from a list still plays. While the Rater
 * layout is skipping rated tracks, that takes over (only unrated ones play).
 */
object RatingFilter {
    /** 0 is Off, else 1 to 5 stars. */
    fun minStars(store: SettingsStore) = store.int(SlopBoxSettings.MIN_STARS).coerceIn(0, 5)
    fun playUnrated(store: SettingsStore) = store.int(SlopBoxSettings.PLAY_UNRATED) != 0

    /**
     * May the automatic track change, Next or (with [backwards]) Previous stop
     * at [mediaId]? Previous isn't held to the Rater's Skip Rated, so you can
     * go back and rate a track again.
     */
    fun passes(store: SettingsStore, mediaId: String, backwards: Boolean = false): Boolean {
        if (Rater.skipping(store)) return backwards || !Rater.rated(mediaId)
        val min = minStars(store)
        if (min == 0) return true
        val r = TagDb.statsOf(mediaId).rating
        return if (r == 0) playUnrated(store) else Rater.stars(r) >= min
    }

    /** The While Playing Screen's note that tracks are being passed over, or null. */
    fun label(store: SettingsStore): String? {
        if (Rater.skipping(store)) return null   // the Rater layout says so itself
        val min = minStars(store).takeIf { it > 0 } ?: return null
        return "★$min+" + if (playUnrated(store)) " & unrated" else ""
    }
}
