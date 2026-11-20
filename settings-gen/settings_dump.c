/*
 * settings_dump: walk Rockbox's real settings table (apps/settings_list.c)
 * and Settings menu tree (apps/menus/*.c), compiled from the unmodified
 * Rockbox source, and print them as JSON.
 *
 * Function pointers (callbacks, formatters, menu functions) are printed as
 * "@0x..." addresses; gen_settings.py turns them into symbol names.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "config.h"
#include "settings.h"
#include "settings_list.h"
#include "menu.h"
#include "sound.h"
#include "lang.h"
#include "option_select.h"

/* global_settings comes from Rockbox's settings.c, linked in. */

/* language_strings (from Rockbox's generated lang_core.c) is filled here
 * from core_language_builtin, in LANG_* id order. */

extern const struct menu_item_ex main_menu_;

static void init_lang(void)
{
    const unsigned char *p = core_language_builtin;
    for (int i = 0; i < LANG_LAST_INDEX_IN_ARRAY; i++) {
        language_strings[i] = (unsigned char *)p;
        p += strlen((const char *)p) + 1;
    }
}

static void json_str(const char *s)
{
    if (!s) { fputs("null", stdout); return; }
    putchar('"');
    for (; *s; s++) {
        unsigned char c = *s;
        if (c == '"' || c == '\\') { putchar('\\'); putchar(c); }
        else if (c == '\n') fputs("\\n", stdout);
        else if (c < 0x20) printf("\\u%04x", c);
        else putchar(c);
    }
    putchar('"');
}

static void json_fn(const void *fn)
{
    if (fn) printf("\"@%p\"", fn); else fputs("null", stdout);
}

/* A string that may be an ID2P() language id or a plain string. */
static void json_lang(int id);

static void json_pstr(const unsigned char *p)
{
    if (!p) { fputs("null", stdout); return; }
    long id = P2ID(p);
    if (id >= 0) json_lang((int)id);
    else json_str((const char *)p);
}

static void json_lang(int id)
{
    if (id < 0) { fputs("null", stdout); return; }
    printf("{\"lang\":%d,\"text\":", id);
    json_str((const char *)str(id));
    putchar('}');
}

static int setting_index(const void *var)
{
    for (int i = 0; i < nb_settings; i++)
        if (settings[i].setting == var)
            return i;
    return -1;
}

/* Every value a numeric setting can take, with the text Rockbox shows for
 * it, from Rockbox's own option_get_valuestring(). */
static void dump_labels(const struct settings_list *s, int min, int max, int step)
{
    char buf[128];
    if (step <= 0) step = 1;
    if (min > max) { int t = min; min = max; max = t; }
    if ((max - min) / step > 4000) return;
    fputs(",\"labels\":[", stdout);
    for (int v = min, first = 1; v <= max; v += step, first = 0) {
        if (!first) putchar(',');
        const char *txt = option_get_valuestring(s, buf, sizeof(buf), v);
        printf("[%d,", v);
        json_str(txt);
        putchar(']');
    }
    putchar(']');
}

static void dump_table_labels(const struct settings_list *s)
{
    char buf[128];
    const struct table_setting *tb = s->table_setting;
    fputs(",\"labels\":[", stdout);
    for (int k = 0; k < tb->count; k++) {
        if (k) putchar(',');
        printf("[%d,", tb->values[k]);
        json_str(option_get_valuestring(s, buf, sizeof(buf), tb->values[k]));
        putchar(']');
    }
    putchar(']');
}

static void dump_setting(int i)
{
    const struct settings_list *s = &settings[i];
    uint32_t f = s->flags;
    int t = f & F_T_MASK;
    printf("{\"index\":%d,\"cfg\":", i);
    json_str(s->cfg_name);
    printf(",\"flags\":%u,\"var_type\":%d", (unsigned)f, t);
    printf(",\"offset\":%ld", (long)((const char *)s->setting - (const char *)&global_settings));
    fputs(",\"title\":", stdout);
    json_lang(s->lang_id);

    /* default */
    fputs(",\"default\":", stdout);
    if (f & F_DEF_ISFUNC)
        json_fn((const void *)s->default_val.func);
    else if (f & F_T_SOUND)
        printf("%d", sound_default(s->sound_setting->setting));
    else if (t == F_T_CHARPTR || t == F_T_UCHARPTR)
        json_str(s->default_val.charptr);
    else if (t == F_T_BOOL)
        fputs(s->default_val.bool_ ? "true" : "false", stdout);
    else if (t == F_T_CUSTOM)
        printf("\"@%p\"", s->default_val.custom);
    else
        printf("%d", s->default_val.int_);

    if (f & F_T_SOUND) {
        int ss = s->sound_setting->setting;
        printf(",\"kind\":\"sound\",\"sound\":{\"setting\":%d,\"min\":%d,\"max\":%d,"
               "\"step\":%d,\"decimals\":%d,\"unit\":",
               ss, sound_min(ss), sound_max(ss), sound_steps(ss),
               sound_numdecimals(ss));
        json_str(sound_unit(ss));
        putchar('}');
        dump_labels(s, sound_min(ss), sound_max(ss), sound_steps(ss));
    } else if ((f & F_BOOL_SETTING) == F_BOOL_SETTING) {
        const struct bool_setting *b = s->bool_setting;
        fputs(",\"kind\":\"bool\",\"bool\":{\"callback\":", stdout);
        json_fn((const void *)b->option_callback);
        fputs(",\"yes\":", stdout); json_lang(b->lang_yes);
        fputs(",\"no\":", stdout); json_lang(b->lang_no);
        fputs(",\"cfg_vals\":", stdout); json_str(b->cfg_vals);
        putchar('}');
    } else if (f & F_INT_SETTING) {
        const struct int_setting *n = s->int_setting;
        printf(",\"kind\":\"int\",\"int\":{\"unit\":%d,\"step\":%d,", n->unit, n->step);
        fputs("\"min\":", stdout);
        if (f & F_MIN_ISFUNC) json_fn((const void *)(intptr_t)n->min); else printf("%d", n->min);
        fputs(",\"max\":", stdout);
        if (f & F_MAX_ISFUNC) json_fn((const void *)(intptr_t)n->max); else printf("%d", n->max);
        fputs(",\"callback\":", stdout); json_fn((const void *)n->option_callback);
        fputs(",\"formatter\":", stdout); json_fn((const void *)n->formatter);
        putchar('}');
        if (!(f & (F_MIN_ISFUNC | F_MAX_ISFUNC)))
            dump_labels(s, n->min, n->max, n->step < 0 ? -n->step : n->step);
    } else if (f & F_CHOICE_SETTING) {
        const struct choice_setting *c = s->choice_setting;
        printf(",\"kind\":\"choice\",\"choice\":{\"count\":%d,\"cfg_vals\":", c->count);
        json_str(c->cfg_vals);
        fputs(",\"callback\":", stdout); json_fn((const void *)c->option_callback);
        fputs(",\"options\":[", stdout);
        for (int k = 0; k < c->count; k++) {
            if (k) putchar(',');
            if (f & F_CHOICETALKS) json_lang(c->talks[k]);
            else json_pstr(c->desc[k]);
        }
        fputs("]}", stdout);
    } else if (f & F_TABLE_SETTING) {
        const struct table_setting *tb = s->table_setting;
        printf(",\"kind\":\"table\",\"table\":{\"unit\":%d,\"count\":%d,\"cfg_vals\":",
               tb->unit, tb->count);
        json_str(tb->cfg_vals);
        fputs(",\"callback\":", stdout); json_fn((const void *)tb->option_callback);
        fputs(",\"formatter\":", stdout); json_fn((const void *)tb->formatter);
        fputs(",\"values\":[", stdout);
        for (int k = 0; k < tb->count; k++)
            printf("%s%d", k ? "," : "", tb->values[k]);
        fputs("]}", stdout);
        dump_table_labels(s);
    } else if (f & F_CUSTOM_SETTING) {
        /* The default as .cfg text, via the setting's own functions. */
        char buf[256] = "";
        const struct custom_setting *cs = s->custom_setting;
        /* Only the ones whose helpers don't need the rest of Rockbox. */
        bool safe = s->cfg_name && (!strncmp(s->cfg_name, "eq ", 3)
                                    || !strcmp(s->cfg_name, "volume limit"));
        if (safe && cs->set_default && cs->write_to_cfg) {
            cs->set_default(s->setting, s->default_val.custom);
            cs->write_to_cfg(s->setting, buf, sizeof(buf));
        }
        fputs(",\"kind\":\"custom\",\"default_cfg\":", stdout);
        json_str(buf);
    } else if (f & F_FILENAME) {
        const struct filename_setting *fn = s->filename_setting;
        fputs(",\"kind\":\"filename\",\"filename\":{\"prefix\":", stdout);
        json_str(fn->prefix);
        fputs(",\"suffix\":", stdout); json_str(fn->suffix);
        printf(",\"max_len\":%d}", fn->max_len);
    } else if (f & F_HAS_CFGVALS) {
        fputs(",\"kind\":\"cfgvals\",\"cfg_vals\":", stdout);
        json_str(s->cfg_vals);
    } else {
        fputs(",\"kind\":\"plain\"", stdout);
    }
    putchar('}');
}

static void dump_menu(const struct menu_item_ex *m, int depth)
{
    int type = m->flags & MENU_TYPE_MASK;
    printf("{\"type\":%d,\"flags\":%u", type, m->flags);
    if (m->flags & MENU_HAS_DESC) {
        fputs(",\"text\":", stdout);
        json_pstr(m->callback_and_desc->desc);
        fputs(",\"callback\":", stdout);
        json_fn((const void *)m->callback_and_desc->menu_callback);
    } else if (m->flags & MENU_DYNAMIC_DESC) {
        fputs(",\"text_fn\":", stdout);
        json_fn((const void *)m->menu_get_name_and_icon->list_get_name);
        fputs(",\"callback\":", stdout);
        json_fn((const void *)m->menu_get_name_and_icon->menu_callback);
    } else if (type == MT_SETTING) {
        fputs(",\"callback\":", stdout);
        json_fn((const void *)m->menu_callback);
    }

    switch (type) {
    case MT_MENU: {
        int n = MENU_GET_COUNT(m->flags);
        fputs(",\"items\":[", stdout);
        for (int i = 0; i < n; i++) {
            if (i) putchar(',');
            dump_menu(m->submenus[i], depth + 1);
        }
        putchar(']');
        break;
    }
    case MT_SETTING:
    case MT_SETTING_W_TEXT:
        printf(",\"setting\":%d", setting_index(m->variable));
        break;
    case MT_FUNCTION_CALL:
        fputs(",\"function\":", stdout);
        json_fn((const void *)m->function->function);
        break;
    case MT_FUNCTION_CALL_W_PARAM:
        fputs(",\"function\":", stdout);
        json_fn((const void *)m->function_param->function_w_param);
        printf(",\"param\":\"@%p\"", m->function_param->param);
        break;
    case MT_RETURN_ID: {
        int n = MENU_GET_COUNT(m->flags);
        fputs(",\"strings\":[", stdout);
        for (int i = 0; i < n; i++) {
            if (i) putchar(',');
            json_pstr((const unsigned char *)m->strings[i]);
        }
        putchar(']');
        break;
    }
    case MT_RETURN_VALUE:
        printf(",\"value\":%d", m->value);
        break;
    }
    putchar('}');
}

int main(void)
{
    init_lang();
    printf("{\"lang_count\":%d,\"settings\":[", LANG_LAST_INDEX_IN_ARRAY);
    for (int i = 0; i < nb_settings; i++) {
        if (i) fputs(",\n", stdout);
        dump_setting(i);
    }
    fputs("],\n\"lang_strings\":[", stdout);
    for (int i = 0; i < LANG_LAST_INDEX_IN_ARRAY; i++) {
        if (i) putchar(',');
        json_str((const char *)str(i));
    }
    fputs("],\n\"menu\":", stdout);
    dump_menu(&main_menu_, 0);
    fputs("}\n", stdout);
    return 0;
}
