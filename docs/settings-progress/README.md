# Settings Assistant — progress

_Last updated: 2026-09-26 · branch `feat/settings-handle` · owner: Swaroop_

## What it is

The part of KAIRO that changes phone settings from natural language, fully on-device.
Users can ask for a setting ("reduce brightness") or describe a problem ("my eyes hurt at night").
The assistant then changes the setting, opens the right system panel, or shows step-by-step guidance with a deep link.

## Status at a glance

| Area | Status |
|---|---|
| Chat UI (text + offline voice input) | ✅ Done |
| Knowledge base of 51 entries (JSON) | ✅ Done |
| Phone info answers: device, storage, battery, software update | ✅ Done, tested on phone (Gemma picks them in 1.6–3.5 s) |
| Wallpaper from the gallery ("set my beach photo as wallpaper") | ✅ Done, tested on phone (home, lock, both) |
| Slow / hot phone checkup + clean up cache, close apps, restart | ✅ Done, tested on phone (instant, no permissions) |
| Wellbeing: screen time, top apps, unlocks, late-night use → app timer / Bedtime / Focus mode; biggest app cache in the checkup | ✅ Built, unit-tested · ⚠️ on the iQOO, HackTracker blocks the Usage access page, so the screen-time card itself is untested |
| Keyword agent | ✅ Done, tested on phone |
| Gemma (LLM) agent: picks setting + action (+ photo), keyword agent as fallback | ✅ Done, tested on phone |
| Speed: skip Gemma when the keywords fully understand the request, or for checkup / reading / eye-strain problems | ✅ Done, tested on phone (13 of 14 example chips answer instantly) |
| Accessibility: touch vibration (direct), low-vision suggestions, guides for inversion, grayscale, color correction, contrast, bold text, Extra dim, animations, display size, magnification, TalkBack, captions, hearing aids | ✅ Done, partly tested on phone (see below) |
| Undo for every direct change (except wallpaper: confirm first instead) | ✅ Done |
| Problem → suggestions (eye strain) | ✅ Done |
| Single app: gallery home → Settings screen | ✅ Done |
| Routing unit test | ✅ Passing (94 phrases) |
| Screen-time maths unit test (`UsageMathTest`) | ✅ Passing |
| Accessibility switch on/off logic (`SecureSwitchesTest`) | ✅ Passing |
| Commit to `feat/settings-handle` | ✅ Committed |
| Floating guide card, AccessibilityService, QS tile, onboarding | ⬜ Not started |

## How to open it

Launch **Kairo Gallery** and tap the sliders icon (top-right) to open the **Settings Assistant**. The ← arrow returns to the gallery.

```bash
adb shell am start -n ai.kairo.gallery/.ui.MainActivity
```

## Architecture

```
SettingsChatScreen ──► SettingsChatViewModel ──► SettingsAgent (interface)
                                                    │  handle(query) / apply(id, action, value) / setWallpaper(uri, screen) / undo(token)
                                                    ▼
                                            LlmSettingsAgent  (Gemma decides; falls back to keywords)
                                                    ▼
                                            KeywordSettingsAgent   (routing + executors)
                                             ├─ SettingsKb      ← assets/settings_kb.json
                                             ├─ QueryParser     (action words, wallpaper screen + photo)
                                             ├─ SystemSettingsController  (Settings.System, needs WRITE_SETTINGS)
                                             ├─ DeviceController          (volume, ringer, DND, flashlight)
                                             ├─ PhoneInfo                 (read-only facts)
                                             └─ Wallpapers                (gallery search → pick → set)
```

- The agent only picks a `setting_id` + `action` from the knowledge base. Kotlin code does the actual change. The model never invents settings paths.
- Responses are typed: `Info`, `Done` (with undo), `Suggestions`, `Guide`, `Facts` (read-only rows + a button), `ChoosePhoto` (wallpaper thumbnails), `OpenPanel`, `NeedsPermission`.
- Some settings need a detail besides the action: for the wallpaper, Gemma returns `"photo":"beach sunset"` (the keyword agent strips the filler words instead), and the action is the screen (`both` / `home` / `lock`).
- The same `SettingsAgent` interface is meant for the floating bubble and a Quick Settings tile later.

### Files

| File | Purpose |
|---|---|
| `app/src/main/assets/settings_kb.json` | Curated settings: tier, synonyms, actions, panel intent, guide steps + intent fallbacks |
| `settings/SettingsModels.kt` | IDs, actions, `IntentSpec`, `UndoToken`, `SettingsResponse`, `SettingsAgent` |
| `settings/SettingsKb.kt` | Loads the JSON; longest-synonym matching |
| `settings/QueryParser.kt` | Detects the action; detects the eye-strain problem |
| `settings/KeywordSettingsAgent.kt` | Routing, executors, undo, permission and guide responses |
| `settings/SystemSettingsController.kt` | Brightness, font scale, timeout, rotation, touch sounds |
| `settings/DeviceController.kt` | Volumes, silent/vibrate, Do Not Disturb, flashlight |
| `settings/PhoneInfo.kt` | Read-only answers: phone info, storage, battery, software update, slow-phone checkup (+ `InfoFormat` helpers) |
| `settings/AccessibilityController.kt` | Accessibility switches in Settings.Secure/Global (`SecureSwitches` table); direct only with WRITE_SECURE_SETTINGS |
| `settings/Wellbeing.kt` | Screen time from Android's usage events (`UsageMath`: one app in front at a time, home screen not counted), `hasUsageAccess()`, `appLabel()` |
| `settings/Wallpapers.kt` | Finds photos with the gallery search, then decodes (EXIF-rotated), center-crops to the screen and sets the tapped one |
| `settings/ui/*` | Chat screen, ViewModel, `SettingsActivity` |
| `app/src/test/.../SettingsKbRoutingTest.kt` | Runs real phrases through the real JSON |

## Supported settings (45) + phone info (6)

| Tier | Settings | How |
|---|---|---|
| **Accessibility** | touch vibration (direct, `WRITE_SETTINGS`); color inversion, grayscale, high contrast text, bold text, Extra dim, animations (guide, or direct + Undo when `WRITE_SECURE_SETTINGS` is granted with adb); color correction, display size, magnification, TalkBack, captions, hearing aids (guide only) | TalkBack is never switched on for the user: it changes how touch works. "My grandma can't read the screen" → text size (Apply), bold text, display size, magnification |
| **Info → wellbeing** (Usage access) | screen time | Today vs yesterday, unlocks, after-midnight use, top 3 apps. Suggests a timer for the top app (≥ 1 h), Bedtime mode (≥ 30 min after midnight), Focus mode (≥ 80 unlocks) |
| **Panel** (Digital Wellbeing) | app timer, Bedtime mode, Focus mode, app info | Opens Google Digital Wellbeing's own screens. From a suggestion, the timer / app info page of that exact app |
| **Info** (read-only) | phone info, storage, battery, software update | Card with facts + a button to the right screen. No permission needed |
| **Info → suggestions** | phone checkup ("my phone is slow / hot") | Checks storage, free memory, temperature, battery saver, days since restart (and whether Kairo is indexing); suggests fixes only for what's wrong, else restart + cleanup |
| **Direct** (confirm first, no Undo) | wallpaper (home, lock or both) | Up to 3 matching gallery photos; the tapped one is set. Apps can't read the current wallpaper, so there's nothing to restore |
| **Direct** (with Undo) | brightness, auto-brightness, text size, screen timeout, auto-rotate, media / ring / alarm volume, silent, vibrate, Do Not Disturb, flashlight, touch sounds | Changed by the app |
| **Panel** | Wi-Fi, mobile data / internet, NFC, cleanup ("clear cache") | System panel pops up (apps can't toggle these since Android 10). Cleanup opens vivo's iManager cleaner, which *can* clear other apps' cache |
| **Guide** | Bluetooth, dark mode, eye protection, airplane mode, location, battery saver, app notifications, hotspot, close apps, restart | Numbered steps + "Take me there" deep link (close apps and restart have steps only) |

**Permissions:** the user grants these on system screens the first time they're needed. The app shows a Grant / Try again card.
- *Modify system settings* (`WRITE_SETTINGS`): brightness, auto-brightness, text size, timeout, rotation, touch sounds.
- *Do Not Disturb access* (`ACCESS_NOTIFICATION_POLICY`): silent mode, Do Not Disturb, and ring volume or vibrate in some states.
- `SET_WALLPAPER` is granted at install (normal permission), so the wallpaper needs no prompt.
- *Usage access* (`PACKAGE_USAGE_STATS`): screen time, and the "biggest app cache" line in the checkup (other apps' cache sizes are private without it).

## On-device test results (iQOO I2501, Android 16, OriginOS 6)

| Test | Result |
|---|---|
| Brightness down (tested by Swaroop) | ✅ |
| Media volume up/down | ✅ |
| Alarm volume + Undo | ✅ |
| Text size + Undo | ✅ `FONT_SCALE` writes work on OriginOS |
| Auto-brightness + Undo | ✅ |
| Touch sounds on / "already off" | ✅ |
| Vibrate + Undo | ✅ |
| Flashlight on + Undo | ✅ |
| Auto-rotate ("already on") | ✅ |
| Silent mode → asks for DND access | ✅ |
| Wi-Fi / NFC panels | ✅ Panel shown (confirmed in logcat) |
| Bluetooth / airplane / dark mode guides | ✅ |
| Unknown request → help message | ✅ |
| Screen timeout | ✅ after fix: see below |
| Gallery → Settings → back navigation | ✅ |
| "Is my phone up to date?" → patch 1 Aug 2026, Play system update 1 Feb 2026; **Check for updates** opens vivo's System update ("Already the latest system version") | ✅ |
| "What Android version do I have?" → "iQOO 15 running Android 16 (OriginOS 6)", 12 GB RAM, 256 GB, 1440 × 3168 · 144 Hz; **Open About phone** works | ✅ |
| "How much storage is left?" → 214 GB free of 256 GB; **Free up space** opens the storage screen | ✅ |
| "Battery health" → 100%, Good, 33.1 °C, 2 cycles; **Battery settings** opens iQOO's own battery manager (`com.iqoo.powersaving`) | ✅ |
| "Set my beach sunset photo as wallpaper" → Gemma: `photo: "beach sunset"` (1.7 s) → 1 match → tap → home + lock set, center-cropped | ✅ |
| "Put the mountains on my lock screen" → `action: lock` → only the lock screen changes | ✅ |
| "Set a dog photo as my wallpaper" with no dog photos → "I couldn't find a photo of "dog"" | ✅ |
| "My phone is slow" → instant checkup: 16% storage used, 2.5 GB of 12 GB memory free, 33.7 °C, battery saver off, last restart 23 h ago → "looks fine" + Restart / Clean up | ✅ |
| **Clean up junk and cache** / "clear cache" → vivo's cleaner (`PhoneCleanActivity2`). The very first time, iManager shows its user agreement instead | ✅ |
| "my phone is getting hot", "my phone is very sluggish" → checkup instantly (keywords); "everything takes forever to open" → Gemma (6.7 s) → checkup | ✅ |
| **Restart your phone** → guide steps | ✅ |
| "How much screen time today?" (no Usage access) → Grant card → **Grant** opens `AppUsageAccessSettingsActivity` on Kairo's switch | ✅ (then covered by HackTracker's organiser-passcode screen) |
| "turn on bedtime mode" → Digital Wellbeing `WindDownActivity`; "set an app timer" → `DashboardActivity` | ✅ |
| "pause distracting apps" → Gemma suggests Focus mode → **Open Focus mode** → `FocusModeConfigActivity` | ✅ |
| Checkup without Usage access → no "Biggest app cache" line, rest unchanged | ✅ |
| Screen-time card, app timer for one app, "Clear X's cache" | ⬜ Needs Usage access (organiser passcode on this phone) or another phone |
| "My grandma can't read the screen" → instant suggestions (text at 100%) → **Apply** → font_scale 1.0 → 1.15 → **Undo** → 1.0 | ✅ |
| Bold text → **Guide me** → "Android only lets system apps switch bold text" + steps | ✅ |
| "turn off haptic feedback" → `haptic_feedback_enabled` 1 → 0 → **Undo** → 1 (OriginOS keeps the write) | ✅ |
| "invert colors" → Gemma: `color_inversion` / `on` → guide | ✅ routed (someone else was using the phone, so the card wasn't checked) |
| Accessibility "Take me there" screens; direct switches with `WRITE_SECURE_SETTINGS` | ⬜ Not opened (HackTracker may guard Accessibility, like Usage access); permission not granted |
| **Regression after the Gemma fast path (2026-09-27):** all 14 example chips + 13 typed requests (info cards, checkup, wallpaper, cleaner, Wi-Fi panel, Bedtime, touch vibration, guides, typo, off-topic) | ✅ All as expected; 40/40 unit tests pass |
| Silent / DND actually switching | ⬜ Needs DND access granted first |
| Ring volume; location / battery saver / hotspot deep links | ⬜ Not yet |

### Findings on OriginOS

- `SCREEN_BRIGHTNESS` range is **0–255**.
- **Screen timeout is capped at 5 min.** Writes of 10 or 30 min are accepted, then reset to 5 min. The agent now reads the value back after writing and tells the user instead of reporting a fake success.
- All 20 deep-link intents in the knowledge base resolve (checked with `pm resolve-activity`). Eye protection opens `NIGHT_DISPLAY_SETTINGS`. Hotspot opens vivo's own `VivoTetherSettingsActivity`.
- `adb shell cmd media_session volume --set` has no effect on OriginOS. Use the volume keys or the app itself to test volume.
- The first `installDebug` can fail until you accept OriginOS's "Install via USB" prompt on the phone.
- `android.settings.SYSTEM_UPDATE_SETTINGS` opens **Google Play services' updater**, not vivo's. vivo's updater (`com.bbk.updater`) has no launcher icon; it opens with `com.bbk.updater.action.START_UPDATERACTIVITY`, so that's tried first.
- The marketing name and skin aren't in `Build`: `ro.vivo.product.release.name` = "iQOO 15", `ro.vivo.os.build.display.id` = "OriginOS 6" (readable by the app through `getprop`). `Build.MODEL` is only "I2501".
- Apps can't check for OS updates (no public API, and Kairo has no INTERNET). The update card shows how old the security patch is and opens the updater.
- **Slow phone:** asked "my phone is slow", Gemma returned generic tips (clean up, close apps) in 5.9 s without looking at the phone. Now any query the keywords route to the checkup skips Gemma, and the prompt tells Gemma to pick `phone_checkup` for slow/hot phones. Gemma still sometimes answers `suggest` with the checkup as one item among generic tips, so any Gemma answer that mentions `phone_checkup` runs the checkup instead.
- **HackTracker** (`com.reskill.hacktracker`, the organisers' app: device admin + accessibility service + its own Usage access) puts an "Enter organiser passcode" screen over the Usage access page. Participants can't grant Usage access on this phone without the organisers.
- All the AOSP accessibility screens resolve on OriginOS 6: `COLOR_INVERSION_SETTINGS`, `ACCESSIBILITY_COLOR_SPACE_SETTINGS` (color correction), `ACCESSIBILITY_COLOR_MOTION_SETTINGS`, `TEXT_READING_SETTINGS` (display size and text), `MAGNIFICATION_SETTINGS`, `CAPTIONING_SETTINGS`, `HEARING_DEVICES_SETTINGS`, `REDUCE_BRIGHT_COLORS_SETTINGS`. TalkBack is installed.
- **Gemma got slower as the list grew:** with 51 entries in the prompt, answers take 2–5 s (1.6 s with 24). Fixed by skipping Gemma when every word of the request is a synonym of the matched setting, an action word or filler (`QueryParser.isSimpleRequest`; negations like "don't" aren't filler). On the iQOO: "is my phone up to date", "how much storage is left", "invert colors", "my eyes hurt at night", "turn off haptic feedback" → no Gemma; "brightnes up" (typo) → Gemma, 4.8 s; "make the screen brighter only in the evening" → Gemma, 2.1 s. The log says which path each request took (`KairoSettingsLlm`).
- The empty chat's example chips no longer fit on one screen (13 chips); the list now scrolls.
- Digital Wellbeing (`com.google.android.apps.wellbeing`) is on the iQOO: `…action.APP_USAGE_DASHBOARD`, `…action.WIND_DOWN` (Bedtime mode), `…action.FOCUS_MODE`, and `android.settings.action.APP_USAGE_SETTINGS` + `EXTRA_PACKAGE_NAME` for one app's timer. The Usage access page accepts `package:ai.kairo.gallery` and opens on Kairo's own switch.
- vivo's cleaner: `com.iqoo.secure.action.SPACE_MANAGER` → `com.vivo.imanager/…PhoneCleanActivity2`. It's also the first "Free up space" target of the storage card.
- **Wallpaper:** one `setBitmap(FLAG_SYSTEM | FLAG_LOCK)` call changed only the home screen while the lock screen had vivo's live video wallpaper. Setting each screen in its own call fixes it.
- **The iQOO currently has no CLIP model and no photos in `Pictures/Kairo`**, so gallery search (and the wallpaper feature) only find what's indexed. Two synthetic test pictures (`test_sunset_beach.jpg`, `test_mountains.jpg`) were added there for the wallpaper test.

## Known gaps / TODO

- OriginOS menu labels in the guide steps (e.g. "Display & brightness → Eye protection") are **not verified** yet.
- Dark mode and eye protection are guide-only. An AccessibilityService could tap through them for the demo.
- No confirmation step yet before disruptive changes (silent, DND). Undo is available.
- There's no "what are my current settings?" reply yet.

## Next steps

1. Set the git identity and commit to `feat/settings-handle`.
2. Grant DND access on the phone; test silent/DND and ring volume.
3. Add a Gemma agent via the existing `llm/Llm.kt` (no second model). The prompt lists the knowledge-base ids and actions; the model returns JSON `{setting_id, action}` or a problem → suggestions answer. Keep `KeywordSettingsAgent` as the fallback when the model isn't loaded.
4. Verify the OriginOS menu labels and update the `guide.steps` entries.
5. Add a floating guide card over Settings, an AccessibilityService showcase (dark mode, eye protection), a Quick Settings tile, and a permissions onboarding screen.

## Running the tests

```bash
./gradlew :app:testDebugUnitTest --tests "ai.kairo.gallery.settings.*"
```

On Windows, set `JAVA_HOME` to Android Studio's `jbr` first and use `gradlew.bat`.
