#!/usr/bin/env python3
"""Refresh the bundled AMS drying presets from Bambu Studio's filament profiles.

Usage: tools/update_drying.py <BambuStudio checkout>
       (writes protocol/src/main/resources/drying_presets.tsv)

Get the profiles with:
  git clone --depth 1 --filter=blob:none --sparse https://github.com/bambulab/BambuStudio.git
  git -C BambuStudio sparse-checkout set resources/profiles/BBL/filament

Bambu Studio's drying dialog (src/slic3r/GUI/AMSDryControl.cpp, DevUtilBackend.cpp) reads
these keys from the filament preset matching a spool's filament_id; each 4-value array is
[AMS 2 Pro idle, AMS HT idle, AMS 2 Pro while printing, AMS HT while printing]. The list it
offers is every instantiated system preset compatible with the printer and nozzle, one per
filament_id, named by the preset's alias (the name before " @").

Columns: filament_id, name, type, temps x4, hours x4, softening °C, heat distortion °C,
fully-dries-on (comma list of 2pro/ht), printers (comma list like "X1 Carbon 0.4").
"""
import json, pathlib, subprocess, sys

root = pathlib.Path(sys.argv[1])
src = root / "resources/profiles/BBL/filament"
OUT = pathlib.Path(__file__).resolve().parent.parent / "protocol/src/main/resources/drying_presets.tsv"

presets = {}
for f in src.glob("*.json"):
    d = json.loads(f.read_text())
    if "name" in d:
        presets[d["name"]] = d


def resolve(name, key):
    while name:
        d = presets.get(name)
        if d is None:
            return None
        if key in d:
            return d[key]
        name = d.get("inherits")
    return None


AMS_TYPES = {"0": "2pro", "1": "ht"}
rows = {}
for name, d in sorted(presets.items()):
    if d.get("instantiation") != "true":
        continue
    fid = resolve(name, "filament_id")
    temps = resolve(name, "filament_dev_ams_drying_temperature")
    hours = resolve(name, "filament_dev_ams_drying_time")
    # Bambu Studio ignores presets whose first temperature or time isn't positive.
    if not fid or not temps or not hours or float(temps[0]) <= 0 or float(hours[0]) <= 0:
        continue
    printers = [p.removeprefix("Bambu Lab ").removesuffix(" nozzle") for p in d.get("compatible_printers", [])]
    row = rows.get(fid)
    if row is None:
        def num(key, default):
            v = resolve(name, key)
            return str(round(float(v[0]))) if v else default
        pad = lambda a: [str(round(float(x))) for x in (a + a[-1:] * 4)[:4]]
        limits = resolve(name, "filament_dev_ams_drying_ams_limitations") or []
        row = rows[fid] = {
            "cols": [fid, name.split(" @")[0], (resolve(name, "filament_type") or ["PLA"])[0],
                     *pad(temps), *pad(hours),
                     num("filament_dev_drying_softening_temperature", "50"),
                     num("filament_dev_ams_drying_heat_distortion_temperature", "0"),
                     ",".join(AMS_TYPES[t] for t in limits if t in AMS_TYPES)],
            "printers": [],
        }
    row["printers"] += [p for p in printers if p not in row["printers"]]

commit = subprocess.run(["git", "-C", str(root), "log", "-1", "--format=%h %cs"], capture_output=True, text=True).stdout.strip()
lines = ["\t".join(r["cols"] + [",".join(sorted(r["printers"]))]) for _, r in sorted(rows.items())]
OUT.write_text(f"# AMS drying presets from Bambu Studio's filament profiles ({commit}), https://github.com/bambulab/BambuStudio\n"
               + "\n".join(lines) + "\n")
print(f"{len(lines)} filaments -> {OUT}")
