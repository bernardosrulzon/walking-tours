#!/usr/bin/env python3
"""Find, licence-check and download a Wikimedia Commons photo for a tour stop.

TOUR_GUIDELINES.md section 6: Commons only, public domain or CC BY / CC BY-SA, never NC or ND,
verified through the Commons API *before* downloading, at least 1080 px wide, then normalised by
tools/resize_photos.py. This script enforces the licence and size rules so a bad image cannot
reach a commit by accident.

Usage:
  python3 tools/commons_photos.py search "<term>" [--limit 3]
      List candidate files with licence, size and dimensions.

  python3 tools/commons_photos.py fetch "File:Name.jpg" <dest-stem> [--width 1600]
                       [--tour <tour.json>] [--stop <stop-id>] [--hero]
      Verify the licence, download a raster at <= width, and write app/src/main/assets/photos/
      <dest-stem>.jpg. With --tour/--stop it also patches that stop's photoAsset (the .webp name),
      photoAttribution and photoSourceUrl in the tour JSON; --hero additionally sets tour.heroImage.
      Without --tour it just prints a ready-to-paste photoAttribution and photoSourceUrl.

Run tools/resize_photos.py --write afterwards: it converts the .jpg to the .webp the app draws
and rewrites the JSON references.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import urllib.parse
import urllib.request

from PIL import Image, ImageOps

ROOT = pathlib.Path(__file__).resolve().parent.parent
PHOTOS = ROOT / "app/src/main/assets/photos"
API = "https://commons.wikimedia.org/w/api.php"
UA = "walking-tours-content/1.0 (personal project; contact via repo)"

# Licences we may use. Everything else is refused, so NC/ND can never slip through.
OK_LICENCE = re.compile(r"^\s*(public domain|pd[- ]|cc0|cc[ -]by([ -]sa)?([ -]\d(\.\d)?)?)\s*$", re.I)
BAD_LICENCE = re.compile(r"(non[- ]?commercial|\bnc\b|no[- ]?deriv|\bnd\b)", re.I)


def api(params: dict) -> dict:
    params = {**params, "format": "json", "formatversion": "2"}
    url = f"{API}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=40) as response:
        return json.load(response)


def strip_html(html: str) -> str:
    text = re.sub(r"<[^>]+>", "", html or "")
    return re.sub(r"\s+", " ", text).strip()


def imageinfo(title: str, width: int = 1600) -> dict | None:
    data = api(
        {
            "action": "query",
            "titles": title,
            "prop": "imageinfo",
            "iiprop": "url|size|mime|extmetadata",
            "iiurlwidth": width,
        }
    )
    pages = data.get("query", {}).get("pages", [])
    if not pages or "imageinfo" not in pages[0]:
        return None
    return pages[0]["imageinfo"][0]


def licence_of(meta: dict) -> str:
    return (meta.get("LicenseShortName", {}).get("value") or meta.get("UsageTerms", {}).get("value") or "").strip()


def cmd_search(term: str, limit: int) -> None:
    data = api(
        {
            "action": "query",
            "generator": "search",
            "gsrsearch": term,
            "gsrnamespace": "6",
            "gsrlimit": limit,
            "prop": "imageinfo",
            "iiprop": "url|size|extmetadata",
        }
    )
    pages = data.get("query", {}).get("pages", [])
    if not pages:
        print("no matches")
        return
    for page in pages:
        info = page.get("imageinfo", [{}])[0]
        meta = info.get("extmetadata", {})
        lic = licence_of(meta)
        ok = bool(OK_LICENCE.match(lic)) and not BAD_LICENCE.search(lic)
        print(f'{"OK " if ok else "-- "} {page["title"]}')
        print(f'      {lic or "unknown licence":<22} {info.get("width", "?")}×{info.get("height", "?")}  '
              f'{strip_html(meta.get("Artist", {}).get("value", ""))[:48]}')


def apply_to_tour(tour_path: str, stop_id: str, dest: str, attribution: str, source_url: str, hero: bool) -> None:
    """Write the photo fields into the tour JSON, keeping 2-space UTF-8 formatting."""
    path = pathlib.Path(tour_path)
    if not path.is_absolute():
        path = ROOT / tour_path
    data = json.loads(path.read_text())
    asset = f"photos/{dest}.webp"
    hit = False
    for stop in data["stops"]:
        if stop["id"] == stop_id:
            stop["photoAsset"] = asset
            stop["photoAttribution"] = attribution
            stop["photoSourceUrl"] = source_url
            hit = True
    if not hit:
        raise SystemExit(f"No stop '{stop_id}' in {path}")
    if hero:
        data["tour"]["heroImage"] = asset
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    print(f'patched {stop_id} -> {asset}' + (" (+heroImage)" if hero else ""))


def cmd_fetch(title: str, dest: str, width: int, tour: str | None, stop: str | None, hero: bool) -> None:
    info = imageinfo(title, width)
    if info is None:
        raise SystemExit(f"Not a file on Commons: {title}")
    meta = info.get("extmetadata", {})
    lic = licence_of(meta)
    if not OK_LICENCE.match(lic) or BAD_LICENCE.search(lic):
        raise SystemExit(f"Refusing {title}: licence {lic!r} is not PD / CC0 / CC BY / CC BY-SA")
    src_w = info.get("width", 0)
    if src_w < 1080:
        raise SystemExit(f"Refusing {title}: only {src_w} px wide, below the 1080 px floor")

    url = info.get("thumburl") or info.get("url")
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=90) as response:
        raw = response.read()
    dest_path = PHOTOS / f"{dest}.jpg"
    with Image.open(__import__("io").BytesIO(raw)) as image:
        image = ImageOps.exif_transpose(image)
        if image.width > width:
            image = image.resize((width, max(1, round(image.height * width / image.width))), Image.LANCZOS)
        image.convert("RGB").save(dest_path, "JPEG", quality=90)
    artist = strip_html(meta.get("Artist", {}).get("value", "")) or "Unknown"
    page_url = info.get("descriptionurl") or f"https://commons.wikimedia.org/wiki/{urllib.parse.quote(title.replace(' ', '_'))}"
    print(f"wrote {dest_path.relative_to(ROOT)}  ({dest_path.stat().st_size // 1024} KB, {lic})")
    attribution = f"Photograph: {artist}, {lic}, via Wikimedia Commons"
    if tour and stop:
        apply_to_tour(tour, stop, dest, attribution, page_url, hero)
    else:
        print()
        print(f'      "photoAsset": "photos/{dest}.webp",')
        print(f'      "photoAttribution": "{attribution}",')
        print(f'      "photoSourceUrl": "{page_url}"')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p_search = sub.add_parser("search")
    p_search.add_argument("term")
    p_search.add_argument("--limit", type=int, default=3)
    p_fetch = sub.add_parser("fetch")
    p_fetch.add_argument("title")
    p_fetch.add_argument("dest")
    p_fetch.add_argument("--width", type=int, default=1600)
    p_fetch.add_argument("--tour", default=None)
    p_fetch.add_argument("--stop", default=None)
    p_fetch.add_argument("--hero", action="store_true")
    args = parser.parse_args()
    if args.command == "search":
        cmd_search(args.term, args.limit)
    else:
        cmd_fetch(args.title, args.dest, args.width, args.tour, args.stop, args.hero)


if __name__ == "__main__":
    main()
