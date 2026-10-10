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

Every part of the tour should leave the walker feeling they learned something real — a mechanism,
a belief, a tension, a story that reframes the place. That is the bar, and it is about balance,
not density: plain sentences are fine when they carry something, and a stop can be short as long
as it teaches. What fails the bar is platitude: generic wonder ("magical", "breathtaking"),
adjectives standing in for observation, throat-clearing openers, stage directions that direct
nothing, and long descriptions where each clause teaches nothing new. Humor, irreverence and
personality are delivery, never a substitute: a joke must land on a fact. When in doubt, ask of
each stop: what will the walker understand here that they didn't five minutes ago? If the answer
is nothing, rewrite.

Assume the walker knows nothing about this city or country. They have no context, and they need
the guide to supply it as part of the tour: explain every local term, currency unit, historical
actor and religious concept inline, on first use, in a breath — who the Mughals were, what a lakh
is, what a panda does, what moksha promises. Never use a word the walker cannot be expected to
know without unpacking it right there. An unexplained term is a door closed in the walker's face.

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
one-line send-off. Aim for 400–550 words; an intro that has to establish a whole unfamiliar context
may run to about five minutes, but clarity first — longer must never mean woollier.

Two structural rules, learned the hard way:

- **The intro is not a stop list.** Never walk through the itinerary stop by stop ("you will meet
  X, then Y, then Z..."). The shape-of-the-walk paragraph stays short — two or three sentences on
  where it starts, how it moves and where it ends. If it reads like a table of contents, cut it.
- **Meaning before itinerary.** The place paragraph comes before the shape paragraph: the walker
  should understand WHY the city matters (why people bring their dead here, why the square
  matters, why the palace was built) before hearing where the walk goes. A walker who knows why
  will forgive any route; a walker with only a route has nothing to care about.

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

## 8. Guide personas: adapt, don't overhaul

The authored narration is the fallback every persona starts from, and it is the spine they keep.
A persona adapts the storyline to its style — restructuring, reframing, adding relevant color and
asides only it would know — but it must not completely overhaul the fallback content. A walker who
heard two personas' versions should recognize the same walk, told differently. Style, interests and
feedback shape *how* it is told; the fallback decides *what* is told.

## 10. Detours: generated side chapters

Detours are country-level deep-dives (history, geopolitics, culture, religion, economy) generated
per tour at runtime — there is no authored text, so nothing here lives in the JSON. The same voice
rules apply: truth first, interests as a lens, plain words for newcomers, no sides taken. Topics
stay at the big picture — broad chapter headings like "Food – what to eat here", never one stop,
sight, dish or battle. Topic titles must state the subject plainly at a glance ("Religion in Turkey
today", not "the politics of the holy space") — neither generic enough to fit anywhere nor so
niche it needs explaining first. Photos are fetched at view time from Wikimedia Commons with the
credit shown underneath, first freely-licensed JPEG wins; a missing photo is an empty panel, never
an error. Detour narrations reuse the narration cache and signature, so regeneration rules are
unchanged.

## 9. Before it ships

- `python3 -c "import json; json.load(open(...))"` parses.
- Stop orders are 1-based sequential; every `photoAsset` file exists.
- All coordinates fall inside the city; every Places match was eyeballed, not blindly applied.
- Fees are in local currency with verify-at-the-gate caveats; accessibility is blunt (steps are steps).
- If an existing tour changed, `CONTENT_VERSION` was bumped.
