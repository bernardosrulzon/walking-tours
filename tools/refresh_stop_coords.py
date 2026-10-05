#!/usr/bin/env python3
"""Refresh bundled stop coordinates from the Google Places API (New).

For each stop it searches Places for "<name>, Istanbul" biased to the Historic Peninsula, then
prints the current coordinate next to the two closest matches so the result can be eyeballed before
anything is written. With --write it patches each stop's lat/lng in the tour JSON in place, keeping
the file's two-space formatting.

The Places API must be enabled for the project and allowed on the key. The key in local.properties
is Android-restricted, so the app's own identity headers are sent on every request, exactly as the
app does (see ai/AiHttp.kt).

Usage:
    python3 tools/refresh_stop_coords.py            # dry run
    python3 tools/refresh_stop_coords.py --write    # apply the best match to every stop
"""

import json
import pathlib
import re
import sys
import urllib.error
import urllib.request
from math import asin, cos, radians, sin, sqrt

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOUR = ROOT / "app/src/main/assets/tours/istanbul.json"
PROPS = ROOT / "local.properties"

ENDPOINT = "https://places.googleapis.com/v1/places:searchText"
FIELD_MASK = "places.displayName,places.formattedAddress,places.location"

# The app's identity, so an Android-restricted key accepts a desktop call (see GOOGLE_SETUP.md).
PACKAGE = "com.walkingtours.app"
CERT = "70AC3E93DE37D579EA5E85264D2CC40E908C7870"

# Rank matches near Sultanahmet so "Blue Mosque" cannot win a hit in another city.
BIAS_CENTER = {"latitude": 41.0086, "longitude": 28.9802}
BIAS_RADIUS_M = 3500.0

# Stops whose Google match is a different thing from the stop the tour means, so the curated
# coordinate is kept: Places returns the palace interior, the park's waterfront address, or a
# neighbourhood/district centroid, while the tour deliberately arrives at a gate, the main entrance,
# or the ferry piers. See each stop's narration and nextStopDirections before changing any of these.
KEEP_CURATED = {
    "topkapi-palace",  # Places: museum interior 360 m in. Tour: Bab-ı Hümayun gate and fountain.
    "gulhane-park",    # Places: Kennedy Caddesi address. Tour: the main gate off the tram line.
    "hoca-pasa",       # Places: neighbourhood centroid. Tour: the lanes behind Sirkeci station.
    "eminonu",         # Places: district centroid. Tour: the waterfront and the ferry piers.
}

# A quicker, safer query per stop where the tour's own name is not what Places indexes.
QUERY_OVERRIDES = {
    "obelisk-of-constantine": "Walled Obelisk, Istanbul",
    "serpentine-column": "Serpent Column, Istanbul",
    "haseki-hurrem-baths": "Haseki Hürrem Sultan Hamamı, Istanbul",
    "egyptian-bazaar": "Spice Bazaar, Istanbul",
    "hagia-sophia": "Hagia Sophia, Istanbul",
    "topkapi-palace": "Topkapı Palace, Istanbul",
    "hoca-pasa": "Hoca Paşa, Fatih, Istanbul",
}


def load_key() -> str:
    for line in PROPS.read_text().splitlines():
        if line.startswith("google.maps.apiKey="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("google.maps.apiKey not found in local.properties")


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_008.8
    d_lat = radians(lat2 - lat1)
    d_lon = radians(lon2 - lon1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lon / 2) ** 2
    return 2 * r * asin(min(1.0, sqrt(a)))


def search(key: str, query: str) -> list[dict]:
    body = json.dumps(
        {
            "textQuery": query,
            "maxResultCount": 5,
            "languageCode": "en",
            "locationBias": {"circle": {"center": BIAS_CENTER, "radius": BIAS_RADIUS_M}},
        }
    ).encode()
    request = urllib.request.Request(
        ENDPOINT,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Goog-Api-Key": key,
            "X-Goog-FieldMask": FIELD_MASK,
            "X-Android-Package": PACKAGE,
            "X-Android-Cert": CERT,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response).get("places", [])
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        print(f"    HTTP {error.code}: {detail[:300]}")
        return []
    except urllib.error.URLError as error:
        print(f"    network error: {error.reason}")
        return []


def patch(text: str, stop_id: str, lat: float, lng: float) -> str:
    """Replace the first lat/lng after this stop's id, keeping everything before and between."""
    pattern = re.compile(
        r'("id":\s*"' + re.escape(stop_id) + r'"(?:.*?)"lat":\s*)([-\d.]+)(,\s*"lng":\s*)([-\d.]+)',
        re.DOTALL,
    )
    new_text, count = pattern.subn(
        lambda m: f"{m.group(1)}{lat:.7f}{m.group(3)}{lng:.7f}",
        text,
        count=1,
    )
    if count != 1:
        raise SystemExit(f"Could not patch lat/lng for stop '{stop_id}'")
    return new_text


def main() -> None:
    write = "--write" in sys.argv
    key = load_key()
    root = json.loads(TOUR.read_text())
    stops = root["stops"]
    text = TOUR.read_text()

    picks: list[tuple[dict, dict]] = []
    for stop in stops:
        query = QUERY_OVERRIDES.get(stop["id"], f'{stop["name"]}, Istanbul')
        print(f'{stop["order"]:>2}  {stop["name"]}')
        print(f'    now  {stop["lat"]:.6f}, {stop["lng"]:.6f}   ({query})')
        places = search(key, query)
        if not places:
            print("    no match — left unchanged")
            continue
        for rank, place in enumerate(places[:2], start=1):
            location = place["location"]
            moved = distance_m(stop["lat"], stop["lng"], location["latitude"], location["longitude"])
            name = place.get("displayName", {}).get("text", "?")
            address = place.get("formattedAddress", "")
            print(
                f'    {rank})   {name:<34} {location["latitude"]:.6f}, {location["longitude"]:.6f}  '
                f'Δ{moved:>5.0f} m'
            )
            if rank == 1:
                print(f'          {address}')
        if stop["id"] in KEEP_CURATED:
            print("    kept  curated coordinate (Places match is a different thing; see KEEP_CURATED)")
        else:
            picks.append((stop, places[0]))
        print()

    if not write:
        print(
            f"Dry run only. {len(picks)} stops would be updated, "
            f"{len(KEEP_CURATED)} kept curated. Re-run with --write to apply."
        )
        return

    for stop, place in picks:
        location = place["location"]
        text = patch(text, stop["id"], location["latitude"], location["longitude"])
    # Fail loudly rather than write a broken asset: the patch edits text, not a parsed document.
    json.loads(text)
    TOUR.write_text(text)
    print(f"Updated {len(picks)} stops in {TOUR.relative_to(ROOT)}; kept {len(KEEP_CURATED)} curated.")


if __name__ == "__main__":
    main()
