<p align="center"><img src="app/src/main/res/drawable-nodpi/ic_launcher_foreground.jpg" width="200" alt="Two grinning travellers take a selfie on a forest path under a Turkish flag"></p>

# Walking Tours

**An audio guide for Istanbul that lives on your phone and refuses to be boring.**

Put your earphones in, pick a walk, and go. The app watches where you are. When you reach a stop it just… starts talking. No tapping, no scrolling, no "and on your left." While you walk it's quiet; the moment you arrive, it isn't.

It works with no signal — on a plane, in a tunnel, in 2019.

---

## The walk

**Historic Istanbul: Hippodrome to the Golden Horn.** Fourteen stops, 3.4 km, about 45 minutes of walking — or a full day if you actually go inside everything, which you should.

You start at a Roman chariot racetrack and finish at a ferry dock where Europe turns into Asia. In between: an Egyptian obelisk older than the city itself, a war memorial cast from melted weapons, a church that became a mosque became a museum became a mosque again, a forest of columns hiding under a shopping street, a palace that ran an empire, and a spice market that still smells like 1664.

There's a second tour inside **Topkapı Palace** — the courtyards, the kitchens, the treasury, and the Harem, where the sultans actually lived, schemed, and kept a diamond the size of a pigeon's egg.

## You choose your guide

Before the first step, the app asks one question — *what kind of explorer are you?* — then hands you four guides, each with their own voice, humour and opinions. They walk the whole route with you.

Not feeling the jokes? Tap **Tune** on any stop and tell them what to change — *more direct*, *fewer asides*, *be a pirate*. They take notes. Change your mind later and you can switch guides entirely.

## While you walk

- **It knows when you arrive.** Geofences, not buttons. Reach a stop and it starts talking on its own.
- **Missed the good bit?** Rewind fifteen seconds, or skip ahead. From the screen or the notification.
- **Look or listen.** Every stop has a photo and a map; the map frames where you are and where you're headed next.
- **Ask anything.** The assistant knows the tour and the city — and politely declines to help with your taxes.
- **Point your camera at something.** Snap a dish, a doorway or a complete mystery and ask what it is. It'll tell you, in context.
- **Join anywhere.** Start at stop nine if that's where you woke up. Nobody's keeping score.

## Run it

You'll need [Android Studio](https://developer.android.com/studio) (for the JDK) and a phone.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Install the **debug** build. That's not laziness: the Google keys are bound to the debug signing fingerprint, and a release build looks like a different app to Google, which then sulks.

## Keys are optional — and yours

The whole tour works with **no keys at all**, using the voice already on your phone. If you want the fancier cloud voice and the AI assistant, bring your own Google keys; they're baked in at build time. Treat them like a wallet and never commit them. The full walkthrough lives in [GOOGLE_SETUP.md](GOOGLE_SETUP.md).

## Under the hood, in one breath

Kotlin and Compose, with a stubborn commitment to working offline: the tour ships inside the app, the audio caches on the device, and the only things that ever touch the internet are the optional voice and the assistant.

## Honest status

Checked on a real phone: arrivals, narration, the notification's rewind and skip, joining mid-tour, the assistant, and a photo of a mystery snack. The map has two engines — a free one (OpenStreetMap) and a fancier one (Google) — and they disagree about what "zoom" means, so the same walk can look very different on two phones. That's on purpose, mostly.

---

*Built for one specific person to walk around one specific city. It does that well.*
