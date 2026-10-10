# Tour authoring guidelines

How to build a walking tour for this app — what goes in the JSON, how the words should sound,
and how the facts get checked. Read this before adding or rewriting a tour.

## 1. The file

One tour is one JSON file in `app/src/main/assets/tours/`, 2-space indent, UTF-8, `\n\n` between
paragraphs. Copy the schema from an existing tour: `tour` (id, title, city, country, summary,
overviewText, distanceKm, totalWalkMinutes, difficulty, bestTimeOfDay, heroImage, imageCredits)
plus `stops` in walking order (order, id, name, category, lat, lng, narration, suggestedMinutes,
entranceFeeTry, entranceFeeNote, isFree, openingHours, accessibility, insiderTip,
nextStopDirections, photoAsset, photoAttribution, photoSourceUrl, triggerRadiusMeters).

- Stop ids are kebab-case and unique across ALL tours, not just within one.
- New files are picked up automatically on the next launch. Edits to an existing tour are NOT —
  bump `CONTENT_VERSION` in `ContentSeeder.kt` so devices re-seed.
- `nextStopDirections` walks to the NEXT stop in order (or states the metro/funicular hop honestly).
  The last stop gets a closing paragraph instead.

## 2. The voice

Second person, present tense, warm, honest, funny when the material allows it. The walker is standing
in front of the thing with earphones in — talk to them, not past them. Facts first, jokes second:
every name, date, number and claim must be true. Prices and hours always come with a verify-at-the-
gate caveat, because they move.

## 3. The intro: explain the place, not just the walk

The overview is NOT a table of contents. Anyone can see the stop list. The intro's job is to give
the walker the city and country they are standing in — the context that makes the stops mean
something — covering the human, religious, cultural, economic and political aspects **to the extent
they matter to this tour**:

- **Human**: who lives here now, what daily life looks like around the route.
- **Religious**: the faiths in play and how they share (or contest) the ground — essential wherever
  shrines, temples, mosques or cremation ghats are stops.
- **Cultural**: what the city is proud of, what it argues about, what an outsider gets wrong.
- **Economic**: what pays for the city (oil, tourism, pilgrimage, government) when it shows.
- **Political**: recent history that shaped the place — wars, revolutions, redevelopments, the
  current order — stated plainly and briefly.

Politics and religion are not off-limits; a guide that dodges them is lying by omission. But the
view stays balanced: where people disagree, say so in one breath ("supporters call it renewal;
critics mourn what was demolished"), never take a side, never propagandize, never dunk. Contested
numbers (death tolls, crowd sizes, costs) are either omitted or attributed.

Structure that works: hook → the place (this section) → the shape of the walk → practicals →
one-line send-off. Aim for 400–550 words total.

## 4. Coordinates: measured, not guessed

Every stop coordinate comes from the Google Places API (New), via `tools/refresh_stop_coords.py`,
which searches, biases to the city, and prints each match with its distance from the current point
so a human can judge. Rules:

- Use the POI pin when it is an exact-name match for the thing the tour means.
- Keep the curated point — and document why in the script's `keep_curated` — when Places returns
  something else: a building centroid instead of the gate, a district instead of the waterfront,
  a bar with the terrace's name.
- `triggerRadiusMeters` 30–80 by site size: tight on linear ghats and lanes, wide on parks and
  waterfronts.

## 5. Distances must survive Google Maps

`distanceKm` and `totalWalkMinutes` describe the route walked end to end, and a walker WILL check
them against Google Maps. Haversine × 1.3 is how undercounts happen — it fails in winding old
sites and hides un-walked legs. So:

- Measure every leg with the Routes API (`travelMode: WALK`), sum the real distances and durations.
- A leg the tour rides or metros is still part of the totals; the directions present the ride as the
  recommended shortcut *against explicit on-foot numbers* ("5.1 km — about seventy minutes on foot
  along busy roads — so do not stroll it").
- Watch for routing artifacts (a 230 m hop routed as 1.4 km around a wall): sanity-check any leg
  far above its straight-line distance and estimate honestly instead of shipping the artifact.
- No number in the prose may contradict the JSON: grep every `distanceKm`/`totalWalkMinutes` change
  against overviewText, narrations and directions.

## 6. Photos: licensed, verified, respectful

Wikimedia Commons only: public domain, CC BY or CC BY-SA. Never NC, never ND. Verify the license
through the Commons API (`imageinfo`/`extmetadata`) BEFORE downloading, then take a ~1280–1600 px
version and keep each file ≤ 600 KB. Record author, license and file-page URL in
`photoAttribution`/`photoSourceUrl`, e.g. `Photograph: Diego Delso, CC BY-SA 4.0, via Wikimedia
Commons`.

Respect beats coverage: no cremation close-ups at Manikarnika, no photo of the Kumari herself
(courtyard exterior only), no bathers. Say the rule in the narration where it applies.

## 7. Before it ships

- `python3 -c "import json; json.load(open(...))"` parses.
- Stop orders are 1-based sequential; every `photoAsset` file exists.
- All coordinates fall inside the city; every Places match was eyeballed, not blindly applied.
- Fees are in local currency with verify-at-the-gate caveats; accessibility is blunt (steps are steps).
- If an existing tour changed, `CONTENT_VERSION` was bumped.
