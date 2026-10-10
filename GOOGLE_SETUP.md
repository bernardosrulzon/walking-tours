# Google Cloud setup — better voice + AI travel assistant

Everything here is **optional**. With no keys at all the app still runs a complete Istanbul tour
using the phone's built-in voice. This guide switches on two upgrades with a single key:

1. **A far better narration voice** (Gemini 3.8 Flash TTS).
2. **An AI travel assistant** that answers questions about each stop (Gemini).

Budget about ten minutes. You only do this once.

---

## What it costs

There is a **free tier every month**, and it is genuinely comfortable for personal use — a full
14-stop tour is roughly 15,000 characters of narration, so you can re-listen to the whole tour many
times a month before paying anything.

| Service | Free tier | After that (approximate, check current pricing) |
|---|---|---|
| Gemini API (Flash models) | free tier, rate-limited | pay-as-you-go per token |

Two things worth knowing:

- Set a **budget alert** (step 2) so you cannot be surprised.
- Narration is **synthesised once per stop and cached on the device**. Re-listening to a stop costs
  nothing, and the tour then plays offline.

Check current prices at [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing).

---

## Step 1 — Create a project

1. Go to <https://console.cloud.google.com/projectcreate>
2. Name it something like `walking-tours` and create it.
3. Make sure it is selected in the project picker at the top of the console.

## Step 2 — Turn on billing, with a safety net

1. Go to <https://console.cloud.google.com/billing> and link a billing account to the project.
2. Then set a budget alert at <https://console.cloud.google.com/billing/budgets>. A €5 budget with
   an email alert at 50% is plenty to catch anything unexpected.

## Step 3 — Enable the Generative Language API

1. Open <https://console.cloud.google.com/apis/library/generativelanguage.googleapis.com>
2. Click **Enable**. Wait for it to finish.

## Step 4 — Create and lock down the Gemini API key

One key does everything now: the travel assistant, the guide personas and the narration voice are all
Gemini models on the Generative Language API, so there is no separate Text-to-Speech key.

An API key sitting in an Android app can be extracted from the APK. Google provides a defence:
restrict the key so it only works when the request genuinely comes from your app, signed by your
signing certificate. **The app sends those identity headers**, so this restriction actually works.

1. Go to <https://console.cloud.google.com/apis/credentials> → **Create credentials** → **API key**.
2. Click **Edit** on the new key.
3. Under **Application restrictions**, choose **Android apps**, then **Add**:
   - **Package name:** `com.walkingtours.app`
   - **SHA-1 fingerprint:** see below
4. Under **API restrictions**, choose **Restrict key** and tick only **Generative Language API**.
5. Save. Copy the key.

### Your SHA-1 fingerprint

This is the debug certificate on the machine where the app was built:

```
70:AC:3E:93:DE:37:D5:79:EA:5E:85:26:4D:2C:C4:0E:90:8C:78:70
```

If you build a **release** version, or build on a different machine, that fingerprint is different.
Get the correct one with:

```bash
# Debug builds
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android | grep SHA1

# Release builds
keytool -list -v -keystore /path/to/your-release.keystore -alias your-alias | grep SHA1
```

You can add **several** fingerprints to the same key — add both debug and release so you do not have
to swap keys later. If you see `API key not valid` or a 403 from a device that works on Wi-Fi but not
elsewhere, a wrong SHA-1 is the usual cause.

## Step 5 — Get the key into the app

1. Go to <https://aistudio.google.com/apikey>
2. Click **Create API key** and choose the **same Google Cloud project** you just made.
3. Copy it into `local.properties` as `google.gemini.apiKey` (see Step 6), then rebuild.

It is fine to restrict this one too (API restrictions → **Generative Language API**). Note that
application restrictions by Android app are not consistently honoured for every Gemini endpoint, so
treat this key carefully: it is fine for personal use, but before you publish an app to other people
you should move it behind a small backend so it never ships to a device.

---

## Step 6 — Give the key to the app

Put it in `local.properties` (this file is gitignored, so it never reaches the repository):

```properties
google.gemini.apiKey=AIza...your-key...
```

Then rebuild:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

With a key present at build time, the cloud voice is switched on automatically.

---

## Step 7 — Switch the voice on

In Settings, turn on **Use Gemini voice**. The status line under it reports whether the key works
(Gemini API working, or not working with the reason), and the cloud voice is used whenever the
switch is on.

The voice is matched automatically to your chosen guide, so a guide never sounds like the wrong
person. There is nothing to pick by hand.

---

## What happens if something goes wrong

The app is built to keep working:

| Problem | What the app does |
|---|---|
| No API key at all | Uses the phone's own voice. Chat shows a card explaining what to add. |
| Invalid or expired key | Falls back to the phone's voice automatically and shows "cloud voice unavailable" next to the engine name. The tour keeps playing. |
| No network mid-tour | Same automatic fallback. Stops already heard are cached, so they still play in the cloud voice offline. |
| Quota exhausted | Same automatic fallback, with the reason shown in Settings. |
| Off-topic question in chat | The assistant declines and offers a travel question instead. |

---

## Where the code lives

| Concern | File |
|---|---|
| Key storage and options | `ai/AiSettings.kt` |
| HTTP + error messages + app identity headers | `ai/AiHttp.kt` |
| Gemini text and speech calls | `ai/GeminiClient.kt`, `ai/GeminiTtsClient.kt` |
| Grounding prompt and travel-only rule | `ai/TravelChatController.kt` |
| Cloud narration, caching, fallback | `audio/GeminiTtsNarrationEngine.kt`, `audio/NarrationRouter.kt` |
| Voice picker | `ui/settings/SettingsScreen.kt` |
| Chat UI and microphone | `ui/chat/ChatScreen.kt` |
