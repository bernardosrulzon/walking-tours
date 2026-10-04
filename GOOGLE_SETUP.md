# Google Cloud setup — better voice + AI travel assistant

Everything here is **optional**. With no keys at all the app still runs a complete Istanbul tour
using the phone's built-in voice. This guide switches on two upgrades:

1. **A far better narration voice** (Google Cloud Text-to-Speech, Chirp 3: HD).
2. **An AI travel assistant** that answers questions about each stop (Google Gemini).

Budget about ten minutes. You only do this once.

---

## What it costs

There is a **free allowance every month** on both services, and it is genuinely comfortable for
personal use — a full 14-stop tour is roughly 15,000 characters of narration, so you can re-listen
to the whole tour many times a month before paying anything.

| Service | Free allowance | After that (approximate, check current pricing) |
|---|---|---|
| Cloud Text-to-Speech, Chirp 3: HD | ~1 million characters/month | a few tens of dollars per million characters |
| Cloud Text-to-Speech, Neural2 | ~1 million characters/month | cheaper than Chirp 3 |
| Gemini API | free tier on Flash models, rate-limited | pay-as-you-go per token |

Two things worth knowing:

- **Billing must be enabled** on the project for Cloud Text-to-Speech, even while you are inside the
  free allowance. Google requires a card on file. Set a **budget alert** (step 2) so you cannot be
  surprised.
- Narration is **synthesised once per stop and cached on the device**. Re-listening to a stop costs
  nothing, and the tour then plays offline.

Check current prices at [Cloud TTS pricing](https://cloud.google.com/text-to-speech/pricing) and
[Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing).

---

## Step 1 — Create a project

1. Go to <https://console.cloud.google.com/projectcreate>
2. Name it something like `walking-tours` and create it.
3. Make sure it is selected in the project picker at the top of the console.

## Step 2 — Turn on billing, with a safety net

1. Go to <https://console.cloud.google.com/billing> and link a billing account to the project.
2. Then set a budget alert at <https://console.cloud.google.com/billing/budgets>. A €5 budget with
   an email alert at 50% is plenty to catch anything unexpected.

## Step 3 — Enable the Text-to-Speech API

1. Open <https://console.cloud.google.com/apis/library/texttospeech.googleapis.com>
2. Click **Enable**. Wait for it to finish.

*(You do not need to enable the Gemini API manually — the key from AI Studio works without it.)*

## Step 4 — Create and lock down the Text-to-Speech API key

An API key sitting in an Android app can be extracted from the APK. Google provides a defence:
restrict the key so it only works when the request genuinely comes from your app, signed by your
signing certificate. **The app sends those identity headers**, so this restriction actually works.

1. Go to <https://console.cloud.google.com/apis/credentials> → **Create credentials** → **API key**.
2. Click **Edit** on the new key.
3. Under **Application restrictions**, choose **Android apps**, then **Add**:
   - **Package name:** `com.walkingtours.app`
   - **SHA-1 fingerprint:** see below
4. Under **API restrictions**, choose **Restrict key** and tick only **Cloud Text-to-Speech API**.
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

## Step 5 — Get a Gemini API key

1. Go to <https://aistudio.google.com/apikey>
2. Click **Create API key** and choose the **same Google Cloud project** you just made.
3. Copy it.

It is fine to restrict this one too (API restrictions → **Generative Language API**). Note that
application restrictions by Android app are not consistently honoured for every Gemini endpoint, so
treat this key as the weaker of the two: it is fine for personal use, but before you publish an app
to other people you should move both keys behind a small backend so they never ship to a device.

---

## Step 6 — Give the keys to the app

You have two options. **Option A needs no rebuild.**

### Option A — paste them in the app (easiest)

1. Open the app and tap the **gear icon** at the top right.
2. Scroll to **Google API keys**.
3. Paste the Text-to-Speech key and the Gemini key.
4. Tap **Test TTS key** and **Test connection and detect model**. Both should report success.

Keys are stored only on this device.

### Option B — build-time defaults

Put them in `local.properties` (this file is gitignored, so they never reach the repository):

```properties
google.api.key=AIza...your-key...
```

Or, if you want different keys per service:

```properties
google.tts.apiKey=AIza...
google.gemini.apiKey=AIza...
```

Then rebuild:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

With a key present at build time, the cloud voice is switched on automatically.

---

## Step 7 — Choose a voice

In Settings, tap **Load voices**. You will get every voice your account can use for the language
selected (British English by default). Chirp 3: HD voices are at the top of the list and are the
ones worth hearing; `Achernar`, `Aoede` and `Zephyr` are good starting points.

Tap **Test** to hear one, then tap a voice in the list to select it.

![AI settings](docs/screenshots/10-ai-settings.png)

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
| Cloud Text-to-Speech calls | `ai/GoogleCloudTtsClient.kt` |
| Gemini calls and model discovery | `ai/GeminiClient.kt` |
| Grounding prompt and travel-only rule | `ai/TravelChatController.kt` |
| Cloud narration, caching, fallback | `audio/GoogleCloudTtsNarrationEngine.kt`, `audio/NarrationRouter.kt` |
| Voice picker and key entry | `ui/settings/SettingsScreen.kt` |
| Chat UI and microphone | `ui/chat/ChatScreen.kt` |
