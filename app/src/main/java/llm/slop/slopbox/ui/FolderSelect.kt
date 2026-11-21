/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/plugins/db_folder_select.c - Copyright (C) 2012 Jonathan Gordon; 2012 Thomas Martitz
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import llm.slop.slopbox.FileOps
import llm.slop.slopbox.PlaylistFiles
import llm.slop.slopbox.SettingsStore

/** A folder in the picker, with Rockbox's states (COLLAPSED, EXPANDED, SELECTED, EACCESS). */
private class Node(val id: String, val name: String, val path: String, val depth: Int) {
    var state = COLLAPSED
    var children: List<Node>? = null
    companion object {
        const val COLLAPSED = 0
        const val EXPANDED = 1
        const val SELECTED = 2
        const val EACCESS = 3
    }
}

/**
 * Rockbox's db_folder_select plugin: the music folder's folders as an
 * indented tree. Tap: closed -> open (or selected, if it has no folders
 * inside) -> selected -> closed. Long press: select or clear all the
 * folders inside. Leaving after a change asks "Save Changes?"; [onDone]
 * gets the new colon-separated path list, or null if nothing was saved.
 */
@Composable
fun FolderSelect(title: String, paths: String, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    val tree = MusicTree.root(ctx)
    var roots by remember { mutableStateOf<List<Node>?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var askSave by remember { mutableStateOf(false) }

    fun load(parent: Node?): List<Node> {
        val t = tree ?: return emptyList()
        val id = parent?.id ?: MusicTree.rootId(t)
        val base = parent?.path ?: PlaylistFiles.pathOf(MusicTree.rootId(t)).trimEnd('/')
        return FileOps.children(ctx, t, id).filter { it.isDir && !it.name.startsWith(".") }
            .sortedWith { a, b -> FileView.strnatcmp(a.name, b.name, true) }
            .map { Node(it.id, it.name, "$base/${it.name}", (parent?.depth ?: -1) + 1) }
    }
    /** child_set_state_expand(). */
    fun expand(n: Node) {
        if (n.children == null) n.children = runCatching { load(n) }.getOrNull()
        n.state = when {
            n.children == null -> Node.EACCESS
            n.children!!.isEmpty() -> Node.SELECTED
            else -> Node.EXPANDED
        }
    }
    /** save_folders(): every selected folder, inside open ones too. */
    fun save(list: List<Node>?): List<String> = list.orEmpty().flatMap { n ->
        when (n.state) {
            Node.SELECTED -> listOf(n.path)
            Node.EXPANDED -> save(n.children)
            else -> emptyList()
        }
    }
    var initial by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        roots = withContext(Dispatchers.IO) {
            val r = load(null)
            // select_paths(): open the way down to each saved folder and select it.
            for (p in paths.split(':').filter { it.isNotBlank() }) {
                var level: List<Node> = r
                val target = p.trimEnd('/')
                while (true) {
                    val n = level.firstOrNull { target.equals(it.path, true) || target.startsWith(it.path + "/", true) } ?: break
                    if (target.equals(n.path, true)) { n.state = Node.SELECTED; break }
                    expand(n)
                    if (n.state != Node.EXPANDED) break
                    level = n.children!!
                }
            }
            r
        }
        initial = save(roots)
    }

    fun flat(list: List<Node>?): List<Node> = list.orEmpty().flatMap { listOf(it) + if (it.state == Node.EXPANDED) flat(it.children) else emptyList() }
    fun leave() {
        val now = save(roots)
        if (now != initial) askSave = true else onDone(null)
    }
    BackHandler(enabled = !askSave) { leave() }

    if (askSave) {
        YesNo(store.catalog.str("LANG_SAVE_CHANGES")) { yes ->
            askSave = false
            onDone(if (yes) save(roots).joinToString(":") else null)
        }
        return
    }
    @Suppress("UNUSED_EXPRESSION") rev
    Column(Modifier.fillMaxSize()) {
        TitleBar(title, onBack = { leave() })
        val r = roots
        if (r == null) { ListNote(store.catalog.str("LANG_WAIT")); return@Column }
        RockList(Modifier.fillMaxSize()) {
            items(flat(r), key = { it.id }) { n ->
                RockRow(
                    " ".repeat(n.depth) + n.name + if (n.state == Node.EACCESS) " ( " + store.catalog.str("LANG_READ_FAILED").replace("%s", "") + ")" else "",
                    icon = when (n.state) {
                        Node.SELECTED -> G.Cursor
                        Node.EXPANDED -> G.ListC
                        Node.EACCESS -> null
                        else -> G.TangoFolder
                    },
                    forceIcon = true,
                    onLongClick = {
                        when (n.state) {
                            Node.EXPANDED -> n.children!!.forEach {
                                it.state = when (it.state) {
                                    Node.SELECTED, Node.EXPANDED -> Node.COLLAPSED
                                    Node.COLLAPSED -> Node.SELECTED
                                    else -> it.state
                                }
                            }
                            Node.SELECTED, Node.COLLAPSED -> {
                                expand(n)
                                if (n.state != Node.EACCESS) { n.state = Node.EXPANDED.takeIf { n.children!!.isNotEmpty() } ?: Node.SELECTED; n.children!!.forEach { it.state = Node.SELECTED } }
                            }
                        }
                        rev++
                    },
                ) {
                    when (n.state) {
                        Node.EXPANDED -> n.state = Node.SELECTED
                        Node.SELECTED -> n.state = Node.COLLAPSED
                        Node.COLLAPSED -> expand(n)
                    }
                    rev++
                }
            }
        }
    }
}
