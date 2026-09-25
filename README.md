# INFINITE

Endless signal. An offline-first portrait Android roguelite about a lone signal fighting its way up an
ever-escalating frequency until the run collapses.

- Auto-attacking duel against 10 enemy archetypes, elites, and a boss every 10 stages
- 19 upgrade card families, 7 rarities, 12 synergies, seeded procedural generation
- Two always-available abilities: **Nova** (area burst) and **Phase** (invulnerable dash)
- Permanent meta upgrades, achievements, codex, local saves, offline signal energy
- Fully deterministic core: the same seed always produces the same offers, waves and events
- Native Kotlin, custom `SurfaceView`/Canvas renderer, no game engine, no runtime dependencies

## Requirements

- Android 7.0 (API 24) or newer
- Portrait orientation

## Install

Download `INFINITE.apk` from the latest GitHub release (or the `INFINITE-android-apk` CI artifact),
then allow installation from your file manager when prompted. The APK is debug-signed for local
distribution, so Android shows an "unknown sources" warning on first install.

## Controls

| Action | Control |
| --- | --- |
| Move | Drag anywhere on the left half of the arena (floating joystick) |
| Attack | Automatic, always firing at the nearest target |
| Nova | Right-side large button, 6s cooldown |
| Phase | Right-side small button, 5s cooldown, brief invulnerability |
| Pause | Top-right button or the system back gesture |
| Menus | Tap the buttons; drag to scroll the upgrade and archive lists |

## Game loop

1. Clear a stage of enemies to earn an upgrade card choice (3-5 options, rerollable).
2. Every fourth stage offers a special event with two exclusive choices.
3. Every tenth stage spawns the Infinite Warden plus escorts.
4. Deaths settle the run: Aether is banked, achievements update, and meta upgrades can be bought.
5. Away time grants offline signal energy, capped at eight hours.

## Meta upgrades

Vitality, Edge, Haste, Aegis, Precision, Omen, Insight, Luck, and Salvage. Ranks are permanent and
cost Aether. Insight adds a card option, Luck improves rare-offer pity, and Salvage increases the
end-of-run payout.

## Architecture

```
app/src/main/java/com/elthon/infinite/
  core/        Pure Kotlin simulation and persistence, no Android imports
    Foundation.kt    BigDecimal math, formatting, Vec2, deterministic xoshiro RNG
    Models.kt        Stats, cards, enemies, run/meta/save state
    Content.kt       Scaling policy, enemy factories, events, achievements
    CardEngine.kt    Stat engine, card generator, synergies, card picks
    Combat.kt        Fixed-step combat: movement, AI, projectiles, statuses
    Progression.kt   Meta purchases, events, achievements, offline rewards
    SaveCodec.kt     Versioned binary save format with CRC32 integrity checks
  platform/    Android glue
    SaveStore.kt     Atomic file persistence on a background writer thread
    AudioEngine.kt   Synthesized sound effects and ambient loop, no audio assets
  ui/          Rendering and input
    GameSession.kt   Screen state machine, game loop, save triggers
    Renderer.kt      Immediate-mode Canvas renderer for every screen
    GameView.kt      SurfaceView render thread, input queue, lifecycle handoff
    TouchState.kt    Floating joystick and hit-region tap handling
    Ui.kt            Theme, paint helpers, text wrapping, shape primitives
  MainActivity.kt    Window setup, immersive mode, lifecycle, back handling
```

The simulation core has no Android dependencies, so it is fully covered by JVM unit tests under
`app/src/test/java`. Rendering, input and persistence are driven from a single dedicated game thread;
the UI thread only enqueues pointer events, which keeps all state single-owner and race-free.

## Determinism

Combat randomness comes from a per-run `Rng` (xoshiro256** seeded through SplitMix64) whose state is
part of the save file. Card generation, enemy type selection, elite rolls, crits, status rolls and
event selection all draw from that stream, so reloading a save or replaying a seed reproduces the same
outcomes. Only cosmetic effects (screen shake, audio) use wall-clock time.

## Development

```bash
./gradlew testDebugUnitTest   # deterministic simulation and save-format tests
./gradlew lintDebug           # Android static analysis
./gradlew assembleDebug       # debug APK
```

CI runs all three on every push and publishes `INFINITE-apk.zip` as a workflow artifact. Pushing to
`main` also updates the `v1.0.0` GitHub release with the APK and its SHA-256 checksum.

## Save data

Progress lives in `files/infinite.save` inside app-private storage. The format is a magic header,
version, payload length, CRC32, then a compact binary payload. Writes go to a temporary file and are
renamed atomically, with the previous save retained as a fallback. Corrupt or truncated files are
rejected instead of crashing, and the profile is rebuilt from defaults.
