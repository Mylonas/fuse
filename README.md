# Fuse

Drop orbs into a well. Two of the same tier that touch **fuse** into the next
one — and a fusion can set off the ones underneath it. That cascade is the game.
The well fills, the line at the top is always waiting, and every run ends with an
obvious better move you should have made.

Pure Kotlin on a `SurfaceView` canvas, procedurally synthesised audio. The
only third-party code is the AdMob SDK; the game itself has no dependencies.

## How it plays

| Input | Action |
| --- | --- |
| Drag | Aim the dropper across the well. |
| Release | Drop. |
| Back | Bail out to the title screen. |

Only the smallest five tiers are ever dealt, so every big orb is something you
built. Scoring is the triangular number of the tier, times the chain length, so
one well-placed drop that sets off four fusions is worth far more than four
separate ones.

**SURGE** — every 18 fusions the well jolts. It usually sets off cascades, and
sometimes rescues a board that had nothing left to fuse. It's the variable-reward
spike in an otherwise pure skill game.

## Why this format

This is the third game in the set, and deliberately pulls a different lever from
the other two. Chroma Core is reflex, Tether is flow; Fuse is the one you play
with no time pressure at all and still cannot put down, because of what the
hyper-casual literature identifies as the engine of the genre — a compulsion loop
of *simple action → immediate feedback → visible progress*
([Game Developer](https://www.gamedeveloper.com/design/admiring-the-game-design-in-hyper-casual-games)):

- **Near-miss tension.** Crossing the line isn't instant death — you get 1.6
  seconds. Watching the pile settle back under the line is the best moment in the
  game, and near-misses are the strongest retention mechanic there is.
- **Every failure is legible.** You always know exactly which drop killed you,
  which is what produces "one more".
- **Compounding progress.** The tier you're chasing is visible on screen the
  whole time, made of orbs you fused yourself.
- **Variable reward** layered on skill, via SURGE and the chance of a long
  cascade.
- **Zero restart friction** — one tap.

## The physics were tuned before they were written

`Well.kt` is Verlet integration plus a positional constraint solver. It was
prototyped in JavaScript and run headlessly with bots first, which caught four
things that would otherwise have shipped:

1. **A settled pile could never fuse.** The merge test demanded 1% *overlap*, but
   the solver resolves resting balls to exactly `r1 + r2`. Only mid-air
   collisions ever merged; some seeds went 55 drops with **zero fusions**. The
   tolerance must be greater than 1. There is a test named after this.
2. **The game was unloseable** — the loss timer required a ball to be *at rest*
   above the line, and SURGE re-jostled everything, resetting it forever. Runs
   went 400 drops without ending. The rest requirement is gone.
3. **A compressed pile flung balls** at 22,000 px/s through the walls. Per
   substep displacement is now clamped to a fraction of the radius.
4. **The well was too big to ever fill**, so dropping everything into one column
   scored 57,000 and never lost. With Suika-like proportions (~9 of the smallest
   orbs across) one column now dies in **14 drops for 400 points**.

After tuning, over 8 seeds with a sensible player model: every run ends, between
47 and 174 drops, reaching tier 7–9, and the pile settles to 0 px/s with nothing
escaping.

## Review pass: what the rig says about the finished game

The same bot rig that shaped the physics was later turned on the design, asking
the three questions that found real defects in the other two games.

**Is SURGE actually a reward, or does it kill you?** It jolts the pile upward and
the loss condition is a ball sitting above the line, so this was the obvious
suspect. Same seeds, surge on versus off:

| | avg drops | avg score | avg biggest tier |
| --- | --- | --- | --- |
| surge every 18 fusions | 94 | 11,842 | 7.8 |
| surge disabled | 83.5 | 9,243 | 7.4 |

Runs are *longer* with it and score 28% higher. 30% of deaths land within 2s of a
surge, but a surge fires roughly every 9s, so ~22% would happen by chance alone —
it carries a little risk on top of a clear net benefit, which is what it was for.

**Does placement actually matter?** The first attempt said no: a bot aiming at
the lowest exposed matching ball scored about the same as one dropping at random.
That turned out to be the same trap Tether fell into — the bot was simply a bad
player. A planner that simulates each candidate column and keeps the one that
scores best while leaving the pile lowest:

| player | median drops | score |
| --- | --- | --- |
| simulates each drop | 147 | 23,027 |
| aims at a match, ignoring pile height | 76 | 12,155 |
| drops at random | 100 | 11,068 |
| always the middle column | 16 | 283 |

Real planning is 2.1× the score of random, and the degenerate strategy dies in 16
drops. Note that greedily chasing matches while letting the pile climb is *worse
than random* — the game's lesson is "chase fusions, but don't build towers", and
unlike a hidden coin flip it is a lesson you can see yourself learning.

**Are deaths telegraphed?** Over 30 runs, the pile was already above the line at
the moment of the fatal drop **every single time** — median 8.5 of the smallest
ball's radii above it. Zero runs ended from a board that still looked safe. The
near-miss grace period is doing its job.

No balance changes came out of this pass.

## Tests

`app/src/test` runs the real simulation on the JVM:

- two equal tiers fuse; different tiers never do
- **a settled pile still fuses** — the regression from bug 1, asserted directly
- nothing escapes the well under a heavy fill; no NaNs
- the pile comes to rest instead of jittering forever
- one column ends the run in under 45 drops
- **every run eventually ends** — a game that can't be lost has no tension
- crossing the line is survivable for a grace period
- drops are rate limited and refused after the run ends
- score and fusion counts only ever rise; chains reset on the next drop
- the same seed replays identically

```bash
gradle testDebugUnitTest
```

## Ads and shipping

Interstitials on game over via the AdMob GMA Next-Gen SDK, plus the UMP consent
SDK for EEA users. Frequency capped so it does not wreck a one-more-go game:
nothing for the first three runs, then at most one per three runs and never
within 90 seconds of the last. Every SDK call is wrapped, so no ad failure or
missing Play Services can affect the game — verified by a CI emulator run on an
image with no Play Services at all.

Ad unit IDs come from Gradle properties and default to Google's official test
IDs, so nothing real is in git, and debug builds are pinned to test IDs because
serving yourself live ads gets AdMob accounts suspended.

**[PLAYSTORE.md](PLAYSTORE.md)** is the step-by-step release guide;
**[PRIVACY.md](PRIVACY.md)** is the privacy policy template Play requires.

## Building

No wrapper JAR is checked in. **Android Studio:** open the folder, let it
generate the wrapper, Run. **Command line** (JDK 17 + Android SDK):

```bash
gradle wrapper && ./gradlew assembleDebug
```

**GitHub Actions:** `android.yml` runs tests, lint, the debug APK, the R8 release
APK and the Play AAB. `emulator.yml` boots an Android 14 emulator, installs the
APK, plays it with synthetic touch input, and fails on a crash, an ANR or a dead
process, uploading screenshots and logcat.

## Layout

```
app/src/main/java/com/mikmy/fuse/
  MainActivity.kt   immersive fullscreen host
  GameView.kt       SurfaceView + render thread, paced to 60fps
  Well.kt           the simulation — no Android types, fully unit tested
  Game.kt           rendering, input, particles, HUD, screens
  Sfx.kt            procedural synth + software mixer over one AudioTrack
```

All tuning constants are in `Tune` at the top of `Well.kt`.

- minSdk 26, targetSdk 35, portrait only.
