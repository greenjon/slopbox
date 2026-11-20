# Post-process settings_dump output into the app asset:
#  - "@0x..." function addresses -> symbol names (from nm)
#  - LANG_* names for every language string (from lang_enum.h)
#
# SPDX-License-Identifier: GPL-2.0-or-later
import json, os, re, subprocess, sys

exe, raw, rb, out = sys.argv[1:5]
build = os.path.dirname(exe)

syms = {}
for line in subprocess.run(["nm", exe], capture_output=True, text=True).stdout.splitlines():
    p = line.split()
    if len(p) == 3:
        syms[int(p[0], 16)] = p[2]

text = open(raw).read()
text = text.replace('"@(nil)"', "null")
text = re.sub(r'"@0x([0-9a-f]+)"',
              lambda m: json.dumps(syms.get(int(m.group(1), 16), "?" + m.group(1))), text)
data = json.loads(text)

# lang_enum.h lists LANG_* in index order.
names = []
for line in open(os.path.join(build, "lang_enum.h")):
    m = re.match(r"\s*(LANG_[A-Z0-9_]+|VOICE_[A-Z0-9_]+)\s*,", line)
    if m:
        names.append(m.group(1))
strings = data.pop("lang_strings")
data["lang"] = {n: strings[i] for i, n in enumerate(names) if i < len(strings) and strings[i]}

rev = subprocess.run(["git", "-C", rb, "rev-parse", "--short", "HEAD"],
                     capture_output=True, text=True).stdout.strip()
data["rockbox_revision"] = rev

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w") as f:
    json.dump(data, f, separators=(",", ":"))
print(f"{len(data['settings'])} settings, {len(data['lang'])} strings, rockbox {rev} -> {out}")
