#!/usr/bin/env python3
"""Normalize bundled tour photographs to the size and format the app actually draws.

Every photograph under app/src/main/assets/photos/ is bundled into the APK and decoded at a
1080 px width cap (see ui/components/Photo.kt), so anything wider than that is bytes in the APK
and decode time nobody ever sees. This script:

  * resizes each image wider than the cap down to it, keeping the aspect ratio and honouring
    EXIF orientation;
  * re-encodes it as a lossy WebP at photographic quality. WebP is ~25% smaller than JPEG at
    the same visual quality, and every Android the app supports (API 26) decodes it with a
    fast platform decoder. AVIF compresses further still but is only decodable from Android 12,
    so it is deliberately not used;
  * rewrites the "photos/<name>.jpg" references in the tour JSONs to the new file names, so
    content and files can never drift apart.

Already-normalized files are left exactly as they are, so a second run changes nothing. Run it
after adding or replacing photos; see TOUR_GUIDELINES.md, section 6.

Requires Pillow:  python3 -m pip install Pillow

Usage:
    python3 tools/resize_photos.py            # dry run: report what would change
    python3 tools/resize_photos.py --write    # convert in place (jpg -> webp, JSONs updated)
"""

import pathlib
import sys
from io import BytesIO

from PIL import Image, ImageOps

ROOT = pathlib.Path(__file__).resolve().parent.parent
PHOTOS = ROOT / "app/src/main/assets/photos"
TOURS = ROOT / "app/src/main/assets/tours"

# The width the app decodes at, straight from AssetPhoto's targetWidthPx. Keep the two in step.
MAX_WIDTH = 1080

# WebP quality: visually clean for photographs at display size, ~25% smaller than a JPEG that
# looks the same.
QUALITY = 80


def normalized(path, force=False):
    """Normalized WebP bytes for one photo, or None when nothing needs doing.

    A file within the width cap is left alone unless [force] is set: the JPG-to-WebP move uses
    the force, an already-WebP file at the right size does not need re-encoding.
    """
    with Image.open(path) as image:
        image = ImageOps.exif_transpose(image)
        if image.width > MAX_WIDTH:
            height = max(1, round(image.height * MAX_WIDTH / image.width))
            image = image.resize((MAX_WIDTH, height), Image.LANCZOS)
        elif not force:
            return None
        buffer = BytesIO()
        # Without the source EXIF: the credit lives in the tour JSON, so the metadata is dead
        # weight.
        image.convert("RGB").save(buffer, "WEBP", quality=QUALITY, method=6)
        return buffer.getvalue()


def rewrite_references(rename, write):
    """Point the tour JSONs at the new file names. Returns the number of references changed."""
    changed = 0
    for path in sorted(TOURS.glob("*.json")):
        text = path.read_text()
        updated = text
        hits = 0
        for old, new in rename.items():
            needle = f'"photos/{old}"'
            hits += updated.count(needle)
            updated = updated.replace(needle, f'"photos/{new}"')
        if hits:
            changed += hits
            if write:
                path.write_text(updated)
    return changed


def main():
    write = "--write" in sys.argv[1:]
    jpgs = sorted(PHOTOS.glob("*.jpg"))
    webps = sorted(PHOTOS.glob("*.webp"))
    before = sum(f.stat().st_size for f in jpgs) + sum(f.stat().st_size for f in webps)
    after = 0
    rename = {}

    # Sources that are still JPEG: every one is converted, so the set ends up uniformly WebP
    # and the JSON references can be renamed in one pass.
    for f in jpgs:
        data = normalized(f, force=True)
        old = f.stat().st_size
        after += len(data)
        rename[f.name] = f.with_suffix(".webp").name
        verb = "wrote" if write else "would convert"
        print(f"{verb:>14}  {f.name:<45} {old // 1024:>5} KB -> {len(data) // 1024:>4} KB")
        if write:
            f.with_suffix(".webp").write_bytes(data)
            f.unlink()

    # Files an earlier run already converted: normalize only if they are still too wide.
    for f in webps:
        data = normalized(f)
        if data is None:
            after += f.stat().st_size
            continue
        old = f.stat().st_size
        after += len(data)
        verb = "wrote" if write else "would shrink"
        print(f"{verb:>14}  {f.name:<45} {old // 1024:>5} KB -> {len(data) // 1024:>4} KB")
        if write:
            f.write_bytes(data)

    references = rewrite_references(rename, write)
    print()
    print(f"{len(jpgs) + len(webps)} photos, {len(jpgs)} converted, {len(webps)} already WebP")
    if rename:
        print(f"{references} tour JSON references rewritten to .webp")
    print(f"total {before / 1e6:.1f} MB -> {after / 1e6:.1f} MB" + ("" if write else " (dry run)"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
