# Walking Tours

An Android app that walks you around Istanbul and tells you what you're looking at.

Earphones in, press start, stroll. When you reach a stop the app notices, starts talking, and shows
you a photograph and a transcript. Missed something? Rewind fifteen seconds. Wondering what that
thing is? Ask it.

Fourteen stops, from the Roman Hippodrome to the spice docks of Eminönü. **3.4 km, 45 minutes of
walking** — or six hours if you actually go inside everything, which you should.

The whole tour lives inside the app. No account, no download, no signal. It works on a plane, in a
tunnel, and in 2019.

| Tour list | Itinerary map | Geofenced arrival |
|---|---|---|
| ![Tour list](docs/screenshots/01-tour-list.png) | ![Itinerary](docs/screenshots/02-tour-detail-map.png) | ![Arrival](docs/screenshots/03-geofence-arrival-playing.png) |

---

## Run it

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

The core tour needs **no API keys at all**. Voice and the AI assistant are optional extras —
see [Keys](#keys-and-what-they-cost).

### Faking your way around Istanbul

Geofencing is hard to test from a sofa. Teleport instead:

```bash
adb emu geo fix 28.9768 41.0054    # the Blue Mosque
adb emu geo fix 28.9784 41.0085    # Basilica Cistern, 300 m away
```

Note the order: **longitude first, then latitude**. Everyone gets this wrong once. Those two points
are deliberately close together — walk between them and you'll watch a stop trigger itself.

---

## What it does

| | |
|---|---|
| **Start** | A spoken introduction to the city plays before you move. Skip it with **Next**. |
| **Walk** | Geofences watch for arrivals. Reach a stop and its narration begins on its own. |
| **Look** | Photo or map, swipe between them. The map frames where you are and where you go next. |
| **Listen** | Play, pause, back, forward, 15-second rewind — in the app and in the notification. |
| **Ask** | A travel assistant that knows the tour and politely refuses to discuss anything else. |
| **Jump** | Join at any stop, or start from one. No walking back to the beginning. |

The notification is a real media notification: progress bar, pause, skip, rewind. Your lock screen
got the memo too.

**Audio stops when you close the app.** Not when you background it — that's what the notification is
for — but when you actually swipe it away.

---

## Under the hood, briefly

Kotlin, Compose, Material 3, Room. minSdk 26.

The tour content is **JSON in `assets/`**, seeded into SQLite on first launch. Adding a tour means
adding a file and its photographs, not writing code.

One `TourSessionManager` owns the state: where you are, which stop is current, what's playing.
Screens observe it; they don't guess.

### There are two maps, and they are not the same map

This is the most surprising thing in the codebase, so here it is up front:

- **OpenStreetMap (osmdroid)** — always available, no key needed, shows you every hotel and
  restaurant in Istanbul because Mapnik is Mapnik.
- **Google Maps** — used when a Maps API key is present *and* the device has the modern renderer.

They agree on almost nothing. osmdroid measures zoom in device pixels; Google measures in
density-independent pixels, so the same nominal zoom renders up to 3× closer. On one phone that
difference cropped a fourteen-stop overview down to eight stops. The fit is a single formula, but
each engine feeds it coordinates in its own unit — see `TourMap.kt` if you enjoy that sort of thing.

Walking routes come from Google's **Routes API** (*not* Directions — that one is legacy and answers
`REQUEST_DENIED`). A stop page only asks for the leg it is standing on — current stop to next — and
each leg is cached by its ordered pair, so flicking back and forth between stops does not refetch.
Polylines are cached in memory only, because the terms say don't store them.

---

## Keys, and what they cost

`local.properties` is gitignored. Every key lives there, and none of them are in this repo.

| Key | What it buys | Billed? |
|---|---|---|
| Google Maps | The nicer base map | No |
| Google TTS | A voice that doesn't sound like 2009 | **Per character** |
| Gemini | The travel assistant | **Per token** |

The Maps key is baked in at build time. TTS and Gemini ship in the build too, so there's no in-app
key entry to get wrong.

**Those keys are real and they're yours.** Treat `local.properties` like a wallet, and rotate
anything that ends up somewhere public. Full setup, restrictions and the Android-key quirks:
[GOOGLE_SETUP.md](GOOGLE_SETUP.md).

---

## If you're picking this up

- **Always install debug builds.** The keys are registered to the debug signing fingerprint; a
  release build looks like a different app and Google will sulk.
- **Never `pm clear` a device you care about.** Tour progress *and* the API keys live in
  SharedPreferences. Wiping app data wipes the keys with it.
- Build with `JAVA_HOME` set, one Gradle build at a time, then `./gradlew --stop`. Memory is tight
  and KSP will die of Metaspace if you crowd it.

---

## Honest status

**Verified on a real device:** geofenced arrival, narration, the notification's rewind and skip
buttons, joining mid-tour, the AI assistant, and the Google key setup end to end.

**Verified only on the emulator:** the whole osmdroid path — the emulator's Play Services only
offers the *legacy* Maps renderer, which can't run at this target SDK. So the Google map path has
been exercised on a phone, but not in CI, and not by me.

**Known rough edges:**

- The app-close animation shows a scale-down. Six attempts to remove it failed, every documented
  workaround was tried and reverted, and they're in the commit history so nobody repeats them.
- Photograph attribution lives in Settings rather than under each image. The licences require it to
  exist somewhere; it exists, and it's the one part of the app written for the lawyers.

---

## The tour

**Historic Istanbul: Hippodrome to the Golden Horn** — 14 stops, 3.4 km, ~45 min walking.

Three thousand years of empires on foot: an Egyptian obelisk, a bronze serpent column, the Blue
Mosque, Hagia Sophia, a basilica sunk beneath a cistern, a palace that ran an empire, and a bazaar
that still does. Six stops charge admission; the app tells you which and roughly how much.

---

*Built for one specific person to walk around one specific city. It does that well.*
