# Critter Café

An Android puzzle game (Kotlin, Jetpack Compose): critters queue at a café carousel and take the seats that match
their tickets. The app ships a 100-level official campaign.

| Module | What |
|---|---|
| `core` | rules, real-time carousel, solver, scoring, level pack format |
| `levelgen` | level generators and the official pack builder |
| `app` | the Android app (`com.phloots.crittercafe`, minSdk 26) |

Docs: `docs/ARCHITECTURE.md`, `docs/DESIGN.md`, `docs/LEVEL-QUALITY.md` (how levels are judged), `docs/SKIN-PLAN.md`,
`docs/ART-PIPELINE.md` (sprites, home art, app icon).

## Build and test

```sh
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                        # core, levelgen and app (Robolectric) unit tests
```

Mechanics prototype ("Critter Café Proto", `com.phloots.crittercafe.proto`, installs next to the main app): three
levels per mechanic, each block in its own theme, then four mixes, every level unlocked. The pack is
`app/src/prototype/assets/levels.json`, made by `python3 tools/prototype_pack.py` (each entry also carries `theme`
and `group`, read by the app only).

```sh
./gradlew :app:assembleDebug -Pprototype   # app/build/outputs/apk/debug/app-proto-debug.apk
```

## Analytics

The real-time game reports each level attempt to GameAnalytics (`data/GameAnalyticsLevels.kt`): a progression
`campaign:level_007` (Start, then Complete with score = stars, or Fail on deadlock, stuck, restart or leaving) and
design events:

- `undo:level_007` for each undo;
- one per hint request, by outcome: `hint:shown:level_007` (a move was highlighted), `hint:no_move:level_007` (the
  solver found no winning move) or `hint:wait:level_007` (asked to wait for the carousel to settle).

A restart is a Fail with `end` = `restart` followed by a new Start; a restart after a loss sends only the new Start,
since the lost attempt already ended. GameAnalytics counts attempts per level itself (`attempt_num`).

Every event carries `level_hash` (CRC32 of the level, so a level rebuilt in place stays separable), `resumed` (1 for
an attempt continued by undo after a loss), `taps`, `undos`, `hints` (hints shown), `hints_no_move` and `seconds` of
game time, all for the current attempt, plus `build` (`versionName-<git short commit>`, with `-dirty` for a build
from uncommitted changes, `unknown` without git) and `pack` (CRC32 of the shipped `levels.json`), both fixed at build
time. Fail also carries `end`.

The keys come from the environment at build time. Without them the build works and analytics is off:

```sh
infisical run --domain https://infisical.app.phloots.com/api --projectId fc6e386a-92ab-4436-bef1-af95fd5b45a5 \
  --env prod -- ./gradlew :app:assembleDebug
```

GameAnalytics stores a device identifier, so the Play Console Data safety form has to declare analytics data
collection.

## The official level pack

The shipped campaign is selected by fitness5 (docs/LEVEL-QUALITY.md section 9) with `OfficialPackF5`: the same plan
and seeds as `OfficialPack`, a fitness5 candidate per slot at the slot's belt, and the shipped level kept unless the
candidate is clearly better (`levelgen/out/campaign-f5.md` records each decision).

```sh
# full fitness5 pass: 2-3 hours on 8 threads, resumable (one checkpoint per slot in levelgen/out/campaign-f5/)
./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.OfficialPackF5Kt --args="--write true"
# patch some levels against the pack as shipped (redoes those slots' checkpoints)
./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.OfficialPackF5Kt --args="--only 37,52 --write true"
# the older fitness4 builder (overwrites the fitness5 campaign on a full run)
./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.OfficialPackKt [--args="--only 37,52"]
```

Writes `app/src/main/assets/levels.json`, `levelgen/out/official.md`, `levelgen/out/official-lines.json` and
`levelgen/out/official-meta.json` (per level, keyed by its content hash as the app's analytics reports it: recipe, seeds,
try, generator commit; for the fitness5 pass also the belt, fitness5, its config id and the floors). Without
`--write true`, `OfficialPackF5` only writes the report and the candidate pack `levelgen/out/campaign-f5-levels.json`.
Mechanics preview (intro + harder levels per v4 mechanic):
`-PreverseMain=com.phloots.crittercafe.levelgen.reverse.MechanicsPreviewKt` -> `levelgen/out/mechanics-preview.md`.

## Install on a phone

1. **Developer options:** Settings › About phone › tap **Build number** 7 times.
2. **USB debugging:** Settings › System › Developer options › **USB debugging** on.
3. Plug in over USB-C, unlock the phone, tick **Always allow from this computer** and tap **Allow**.
4. `adb devices` should list the phone as `device`. Then:
   ```sh
   adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Builds before the rename used the app id `com.phloots.busjam`; that app installs side by side with
   `com.phloots.crittercafe`, so remove it with `adb -s <serial> uninstall com.phloots.busjam`.
   Debug builds only: `adb -s <serial> shell am broadcast -a com.phloots.crittercafe.RESET_INTROS -p com.phloots.crittercafe`
   forgets which first-time mechanic intros were shown.

- **Stuck at `unauthorized`** (the phone may still say "USB debugging connected"): Developer options › **Revoke USB
  debugging authorizations**, unplug, unlock, replug and accept the prompt. If no prompt appears, set the USB
  notification to **File transfer**.
- **`Shell does not have permission to access user 11`** during install: the phone has a second profile (such as
  Private Space). The app still installs into the main profile.
- `install -r` keeps saved progress. Switching between a debug and a release-signed build needs an uninstall first,
  which wipes progress.
