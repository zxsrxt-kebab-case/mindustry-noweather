# No Weather

A tiny client-side Mindustry mod that disables weather visuals — snow, rain, sandstorms, spore storms — and mutes weather sounds.

Weather **gameplay** effects (unit slowdown, fire extinguishing, etc.) are untouched, and the mod is marked `hidden`, so it is safe to use on multiplayer servers.

## Features

- Completely skips weather rendering (no particles, no fog overlay)
- Silences weather sounds (rain, wind)
- In-game toggle: **Settings → Weather → Hide weather effects** (English and Russian localization)
- Works instantly, no restart needed when toggling

## Installation

1. Download `no-weather-mod-x.x.x.jar` from [Releases](../../releases)
2. Drop it into your Mindustry mods folder:
   - Windows: `%AppData%\Mindustry\mods`
   - Linux: `~/.local/share/Mindustry/mods`
   - macOS: `~/Library/Application Support/Mindustry/mods`
3. Restart the game

Requires Mindustry v158+ (Java mod, desktop only).

## How it works

Mindustry's `WeatherState.draw()` checks the `showweather` setting and skips all rendering when it is `false`. The mod flips that setting based on its own toggle, and additionally forces the opacity of active weather entities to zero each frame, which silences weather sound loops.

## Building

```sh
./gradlew jar
```

The jar appears in `build/libs/`. Requires JDK 17+ (if your system Java is newer than Gradle supports, set `org.gradle.java.home` in `gradle.properties`).

## License

[MIT](LICENSE)
