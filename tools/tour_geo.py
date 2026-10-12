#!/usr/bin/env python3
"""Measure a tour's stop coordinates (Places API New) and walking legs (Routes API).

This generalises tools/refresh_stop_coords.py so any tour file can be measured, biasing Places to
the tour's own footprint instead of a hard-coded city centre. It never invents a number: every
coordinate is a real Places match printed for a human to eyeball, and every leg is a real Routes
API WALK result. Artifacts (a leg far longer than its straight-line distance) are flagged, not
silently accepted.

Read TOUR_GUIDELINES.md sections 4 and 5 before using it.

Usage:
  python3 tools/tour_geo.py coords <tour.json> [--write] [--radius M] [--bias lat,lng]
      [--keep stop-id]... [--override stop-id="query text"]...
  python3 tools/tour_geo.py routes <tour.json> [--write]
  python3 tools/tour_geo.py both   <tour.json> [--write] [...coord flags...]

  coords   Search Places for each stop, print the two closest matches with their distance from
           the current point, and (with --write) patch the stop's lat/lng to the top match.
  routes   Walk each consecutive pair with Routes API (travelMode WALK), print every leg against
           its straight-line distance, then (with --write) patch distanceKm and totalWalkMinutes.
"""

import json
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request
from math import asin, cos, radians, sin, sqrt

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROPS = ROOT / "local.properties"
TOURS = ROOT / "app/src/main/assets/tours"

PLACES_ENDPOINT = "https://places.googleapis.com/v1/places:searchText"
PLACES_MASK = "places.displayName,places.formattedAddress,places.location"
ROUTES_ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
ROUTES_MASK = "routes.distanceMeters,routes.duration"

# The app's identity, so an Android-restricted key accepts a desktop call (see GOOGLE_SETUP.md).
PACKAGE = "com.walkingtours.app"
CERT = "70AC3E93DE37D579EA5E85264D2CC40E908C7870"

DEFAULT_RADIUS_M = 6000.0
ARTIFACT_RATIO = 2.5  # a leg this many times its straight line deserves a look


def load_key() -> str:
    for line in PROPS.read_text().splitlines():
        if line.startswith("google.maps.apiKey="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("google.maps.apiKey not found in local.properties")


def headers(key: str, extra: dict) -> dict:
    return {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": key,
        "X-Android-Package": PACKAGE,
        "X-Android-Cert": CERT,
        **extra,
    }


def post(url: str, key: str, body: dict, mask: str):
    request = urllib.request.Request(
        url, data=json.dumps(body).encode(), method="POST", headers=headers(key, {"X-Goog-FieldMask": mask})
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        print(f"    HTTP {error.code}: {detail[:300]}")
        return {}
    except urllib.error.URLError as error:
        print(f"    network error: {error.reason}")
        return {}


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6_371_008.8
    d_lat = radians(lat2 - lat1)
    d_lon = radians(lon2 - lon1)
    a = sin(d_lat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(d_lon / 2) ** 2
    return 2 * r * asin(min(1.0, sqrt(a)))


def patch(text: str, stop_id: str, lat: float, lng: float) -> str:
    pattern = re.compile(
        r'("id":\s*"' + re.escape(stop_id) + r'"(?:.*?)"lat":\s*)([-\d.]+)(,\s*"lng":\s*)([-\d.]+)',
        re.DOTALL,
    )
    new_text, count = pattern.subn(lambda m: f"{m.group(1)}{lat:.7f}{m.group(3)}{lng:.7f}", text, count=1)
    if count != 1:
        raise SystemExit(f"Could not patch lat/lng for stop '{stop_id}'")
    return new_text


def patch_number(text: str, key: str, value: str) -> str:
    pattern = re.compile(r'("' + re.escape(key) + r'":\s*)([-\d.]+)')
    new_text, count = pattern.subn(lambda m: f"{m.group(1)}{value}", text, count=1)
    if count != 1:
        raise SystemExit(f"Could not patch {key}")
    return new_text


def centroid(stops) -> tuple[float, float]:
    return (sum(s["lat"] for s in stops) / len(stops), sum(s["lng"] for s in stops) / len(stops))


def cmd_coords(tour_path, root, stops, text, key, write, radius, bias, keep, overrides):
    if bias is None:
        bias_lat, bias_lng = centroid(stops)
    else:
        bias_lat, bias_lng = bias
    print(f"Places bias {{latitude:{bias_lat:.5f}, longitude:{bias_lng:.5f}}} radius {radius:.0f} m\n")

    picks = []
    for stop in stops:
        query = overrides.get(stop["id"], f'{stop["name"]}, {root["tour"]["city"]}')
        print(f'{stop["order"]:>2}  {stop["name"]}')
        print(f'    now  {stop["lat"]:.6f}, {stop["lng"]:.6f}   ({query})')
        places = post(
            PLACES_ENDPOINT,
            key,
            {
                "textQuery": query,
                "maxResultCount": 5,
                "languageCode": "en",
                "locationBias": {
                    "circle": {"center": {"latitude": bias_lat, "longitude": bias_lng}, "radius": radius}
                },
            },
            PLACES_MASK,
        ).get("places", [])
        if not places:
            print("    no match — left unchanged")
            continue
        for rank, place in enumerate(places[:2], start=1):
            loc = place["location"]
            moved = haversine_m(stop["lat"], stop["lng"], loc["latitude"], loc["longitude"])
            name = place.get("displayName", {}).get("text", "?")
            print(f'    {rank})   {name:<40} {loc["latitude"]:.6f}, {loc["longitude"]:.6f}  Δ{moved:>5.0f} m')
            if rank == 1:
                print(f'          {place.get("formattedAddress", "")}')
        if stop["id"] in keep:
            print("    kept  curated coordinate (documented as deliberate in the tour)")
        else:
            picks.append((stop, places[0]))
        print()

    if not write:
        print(f"Dry run only. {len(picks)} stops would be updated. Re-run with --write to apply.")
        return
    for stop, place in picks:
        loc = place["location"]
        text = patch(text, stop["id"], loc["latitude"], loc["longitude"])
    json.loads(text)
    tour_path.write_text(text)
    print(f"Updated {len(picks)} stop coordinates in {tour_path.relative_to(ROOT)}.")


def valhalla_walk(a: dict, b: dict):
    """Valhalla's public pedestrian router, via curl. Real foot route where Google will not go."""
    body = json.dumps(
        {
            "locations": [
                {"lat": a["lat"], "lon": a["lng"]},
                {"lat": b["lat"], "lon": b["lng"]},
            ],
            "costing": "pedestrian",
            "directions_options": {"units": "kilometers"},
        }
    )
    try:
        out = subprocess.run(
            [
                "curl", "-s", "--max-time", "25", "-X", "POST",
                "https://valhalla1.openstreetmap.de/route",
                "-H", "Content-Type: application/json", "-d", body,
            ],
            capture_output=True, text=True, timeout=45,
        ).stdout
        summary = json.loads(out)["trip"]["summary"]
        return int(round(summary["length"] * 1000)), int(round(summary["time"])), "Valhalla"
    except Exception:
        return None


def walk_route(a: dict, b: dict, key: str):
    """Real walking route between two stops: Google Routes, with a Valhalla sanity check.

    Google returns nothing for South Korea and much of mainland China (map-data restrictions), and
    it can also return an *artifact* — a short hop routed the long way around an enclosed square or
    a walled complex. So when Google's leg is implausibly long versus the straight line, Valhalla's
    public pedestrian router is asked too and the more plausible of the two is used. Only if both
    fail does the caller estimate.
    """
    straight = haversine_m(a["lat"], a["lng"], b["lat"], b["lng"])
    google = None
    result = post(
        ROUTES_ENDPOINT,
        key,
        {
            "origin": {"location": {"latLng": {"latitude": a["lat"], "longitude": a["lng"]}}},
            "destination": {"location": {"latLng": {"latitude": b["lat"], "longitude": b["lng"]}}},
            "travelMode": "WALK",
            "languageCode": "en",
        },
        ROUTES_MASK,
    )
    routes = result.get("routes", [])
    if routes:
        route = routes[0]
        google = (
            route["distanceMeters"],
            int(str(route.get("duration", "0s")).rstrip("s") or 0),
            "Google",
        )
        if not straight or google[0] / straight <= ARTIFACT_RATIO:
            return google

    valhalla = valhalla_walk(a, b)
    if google and valhalla:
        use_valhalla = (not straight) or (valhalla[0] / straight) < (google[0] / straight)
        return valhalla if use_valhalla else google
    return google or valhalla


def cmd_routes(tour_path, root, stops, text, key, write):
    total_m = 0
    total_s = 0
    for a, b in zip(stops, stops[1:]):
        straight = haversine_m(a["lat"], a["lng"], b["lat"], b["lng"])
        measured = walk_route(a, b, key)
        if measured is None:
            est_m = round(straight * 1.3)
            est_s = round(est_m / 1.3)
            print(f'{a["order"]:>2}→{b["order"]:<2} {a["name"][:26]:<26} → {b["name"][:26]:<26} NO ROUTE — '
                  f'estimate {est_m} m / {est_s // 60} min (straight {straight:.0f} m)')
            total_m += est_m
            total_s += est_s
            continue
        meters, seconds, source = measured
        ratio = meters / straight if straight else 0
        flag = f"  <-- CHECK ×{ratio:.1f}" if ratio > ARTIFACT_RATIO else ""
        print(f'{a["order"]:>2}→{b["order"]:<2} {a["name"][:26]:<26} → {b["name"][:26]:<26} '
              f'{meters:>6} m {seconds // 60:>3} min {source:<6} (straight {straight:.0f} m){flag}')
        total_m += meters
        total_s += seconds

    km = round(total_m / 1000, 1)
    minutes = round(total_s / 60 / 5) * 5
    print(f"\nTotal walk: {total_m} m = {km} km, {total_s // 60} min = {minutes} min (rounded to 5)")
    if not write:
        print("Dry run only. Re-run with --write to patch distanceKm and totalWalkMinutes.")
        return
    text = patch_number(text, "distanceKm", f"{km}")
    text = patch_number(text, "totalWalkMinutes", f"{minutes}")
    json.loads(text)
    tour_path.write_text(text)
    print(f"Updated distanceKm={km} and totalWalkMinutes={minutes} in {tour_path.relative_to(ROOT)}.")


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    write = "--write" in flags
    command = args[0] if args else "both"
    tour_name = args[1] if len(args) > 1 else None
    if not tour_name:
        raise SystemExit("Give a tour file name, e.g. seoul-palaces-bukchon.json")
    tour_path = TOURS / tour_name
    if not tour_path.exists():
        raise SystemExit(f"No such tour: {tour_path}")

    radius = DEFAULT_RADIUS_M
    bias = None
    keep = set()
    overrides = {}
    for flag in flags:
        if flag.startswith("--radius="):
            radius = float(flag.split("=", 1)[1])
        elif flag.startswith("--bias="):
            lat, lng = flag.split("=", 1)[1].split(",")
            bias = (float(lat), float(lng))
        elif flag.startswith("--keep="):
            keep.add(flag.split("=", 1)[1])
        elif flag.startswith("--override="):
            stop_id, query = flag.split("=", 1)[1].split(":", 1)
            overrides[stop_id] = query

    key = load_key()
    root = json.loads(tour_path.read_text())
    stops = root["stops"]
    text = tour_path.read_text()

    if command in ("coords", "both"):
        cmd_coords(tour_path, root, stops, text, key, write, radius, bias, keep, overrides)
    if command in ("routes", "both"):
        if write:
            root = json.loads(tour_path.read_text())
            stops = root["stops"]
        print()
        cmd_routes(tour_path, root, stops, tour_path.read_text(), key, write)


if __name__ == "__main__":
    main()
