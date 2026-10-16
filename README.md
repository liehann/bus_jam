# Critter Café

An Android puzzle game (Kotlin, Jetpack Compose): critters queue at a café carousel and take the seats that match
their tickets. The app ships a 100-level official campaign.

| Module | What |
|---|---|
| `core` | rules, real-time carousel, solver, scoring, level pack format |
| `levelgen` | level generators and the official pack builder |
| `app` | the Android app (`com.phloots.busjam`, minSdk 26) |

Docs: `docs/ARCHITECTURE.md`, `docs/DESIGN.md`, `docs/LEVEL-QUALITY.md` (how levels are judged), `docs/SKIN-PLAN.md`,
`docs/ART-PIPELINE.md` (sprites, home art, app icon).

## Build and test

```sh
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                        # core, levelgen and app (Robolectric) unit tests
```

## The official level pack

```sh
# full rebuild: 25-35 minutes, uses every core
./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.OfficialPackKt
# rebuild only some levels and splice them into the existing pack
./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.jam.levelgen.reverse.OfficialPackKt --args="--only 37,52"
```

Writes `app/src/main/assets/levels.json`, `levelgen/out/official.md` and `levelgen/out/official-lines.json`.

## Install on a phone

1. **Developer options:** Settings › About phone › tap **Build number** 7 times.
2. **USB debugging:** Settings › System › Developer options › **USB debugging** on.
3. Plug in over USB-C, unlock the phone, tick **Always allow from this computer** and tap **Allow**.
4. `adb devices` should list the phone as `device`. Then:
   ```sh
   adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
   ```

- **Stuck at `unauthorized`** (the phone may still say "USB debugging connected"): Developer options › **Revoke USB
  debugging authorizations**, unplug, unlock, replug and accept the prompt. If no prompt appears, set the USB
  notification to **File transfer**.
- **`Shell does not have permission to access user 11`** during install: the phone has a second profile (such as
  Private Space). The app still installs into the main profile.
- `install -r` keeps saved progress. Switching between a debug and a release-signed build needs an uninstall first,
  which wipes progress.
