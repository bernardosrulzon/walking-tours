# tools/

Scripts for authoring and maintaining the bundled tour content. All are run from the repo root.

## Measuring a tour — `tour_geo.py`

Snaps stop coordinates to the Google Places API (New) and measures every walking leg, then writes
`distanceKm` / `totalWalkMinutes`. It biases Places to the tour's own footprint, so it works for any
city without a per-tour config.

```bash
python3 tools/tour_geo.py coords <tour>.json            # dry run: Places matches + distance Δ
python3 tools/tour_geo.py coords <tour>.json --write [--bias=lat,lng] [--radius=6000] \
    [--keep=<stop-id>]... [--override=<stop-id>:<better query>]...
python3 tools/tour_geo.py routes <tour>.json [--write]  # per-leg walk, summed to the totals
python3 tools/tour_geo.py both <tour>.json --write
```

- `--keep` keeps a curated coordinate when Places returns a different thing (a district centroid, a
  bar with the same name, a gate vs an interior). `--override` supplies a better query per stop.
- Routing order: Google Routes API (`travelMode: WALK`), then the **Valhalla** public pedestrian
  router. Google legally returns *no* route for **South Korea** and much of **mainland China**, so
  tours there are routed by Valhalla — those legs are still real measured walks, marked `Valhalla`.
- Any leg longer than 2.5× its straight line is flagged `CHECK`: fix the coordinates or explain it
  honestly (a wall, a one-way system, a river). Never ship a routing artifact.

## Photos — `commons_photos.py`

Finds, licence-checks and downloads a Wikimedia Commons photo, then patches the tour JSON.

```bash
python3 tools/commons_photos.py search "<subject>"                  # candidates + licence + size
python3 tools/commons_photos.py fetch "File:Name.jpg" <dest-stem> \
    --tour <tour>.json --stop <stop-id> [--hero]
```

- Accepts only public domain / CC0 / CC BY / CC BY-SA and images ≥ 1080 px; refuses NC and ND. Writes
  `app/src/main/assets/photos/<dest-stem>.jpg`.
- With `--tour`/`--stop` it sets that stop's `photoAsset` (the `.webp` name), `photoAttribution` and
  `photoSourceUrl`; `--hero` also sets `tour.heroImage`.

## Normalising photos — `resize_photos.py`

```bash
python3 tools/resize_photos.py            # dry run
python3 tools/resize_photos.py --write    # convert jpg -> webp, rewrite JSON references
python3 tools/resize_photos.py --write --prefix=seoul-,busan-   # only a batch's own photos
```

`--prefix` limits the pass to specific photo families, so a batch never converts an unrelated
in-flight set elsewhere in the repo. Re-running must report no changes.

## Photo-request manifests (batched authoring)

Tour builders do **not** fetch photos. Each builder writes one manifest describing every image the
tour needs; a separate runner turns manifests into licence-checked files. The manifest folder is a
temp directory (currently `/private/var/folders/q0/r0pmqdc15258fg_fnszhr4n80000gn/T/opencode/photo-requests/`),
one `<tour-id>.json` per tour plus a `README.md` with the schema. This keeps the slow, throttled
network work out of the writing agents and lets both run in parallel.
