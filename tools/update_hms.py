#!/usr/bin/env python3
"""Refresh the bundled HMS / print-error descriptions from Bambu Lab's public list.

Usage: tools/update_hms.py   (writes protocol/src/main/resources/hms_en.tsv)

Source: https://e.bambulab.com/query.php?lang=en (the list Bambu's own apps and wiki use).
Format: "<kind>\t<code>\t<text>" per line; kind is H (HMS, 16 hex digits) or E (print
error, 8 hex digits). Entries without text are dropped.
"""
import json, pathlib, urllib.request

URL = "https://e.bambulab.com/query.php?lang=en"
OUT = pathlib.Path(__file__).resolve().parent.parent / "protocol/src/main/resources/hms_en.tsv"

# The server rejects Python's default User-Agent.
req = urllib.request.Request(URL, headers={"User-Agent": "Mozilla/5.0 (layerwatch hms-update)"})
data = json.load(urllib.request.urlopen(req, timeout=60))["data"]
rows = []
for kind, key in (("H", "device_hms"), ("E", "device_error")):
    for e in data[key]["en"]:
        text = " ".join((e.get("intro") or "").split())
        if text:
            rows.append(f"{kind}\t{e['ecode'].upper()}\t{text}")
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(f"# Bambu Lab HMS/error texts, list version {data['device_hms']['ver']}, from {URL}\n" + "\n".join(sorted(rows)) + "\n")
print(f"{len(rows)} entries -> {OUT}")
