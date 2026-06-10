# No Weather

A tiny client-side Mindustry mod with visual tweaks: it disables weather visuals — snow, rain, sandstorms, spore storms — mutes weather sounds, and can hide environment fog/haze and map edge darkness.

Weather **gameplay** effects (unit slowdown, fire extinguishing, etc.) are untouched, and the mod is marked `hidden`, so it is safe to use on multiplayer servers.

## Features

All toggles live in **Settings → Visual tweaks** (English and Russian localization) and work instantly, no restart needed:

- **Hide weather effects** — completely skips weather rendering and silences weather sounds (rain, wind)
- **Hide environment fog/haze** — removes ambient env renderers: Erekir clouds/fog, heat shimmer, underwater tint
- **Remove map edge darkness** — disables the dark border fade around the map
- **Enemy/ally turret ranges** — dashed range circles drawn on the map (enemy red, ally green)
- **Health bars** — compact HP bars over damaged units and buildings
- **Floating damage numbers** — damage popups over targets (yellow for enemies, red when your team takes damage)
- **Highlight my blocks** — outlines blocks you placed this session, handy on busy multiplayer servers
- **DPS by ammo** — every item turret's info dialog gets a computed single-target DPS line per ammo type, with the best one highlighted (accounts for reload multipliers, splash and frag bullets)
- **Next wave preview** — HUD panel under the minimap with the upcoming wave composition: unit types, counts (per spawn point) and shields
- **Core resource rates** — HUD panel with ±items/s for every core resource, averaged over 5 seconds
- **Warning icons** — blinking markers over your turrets without ammo and generators without fuel
- **Drill yield on hover** — point at an ore tile to see items/s for every drill that can mine it (full coverage, no boost)
- **Power grid panel** — total power balance per second and battery charge percentage across all your networks
- **Projector ranges** — dashed circles for built mend (green), overdrive (orange) and force (team color) projectors
- **Spawn markers** — red drop-zone circles with a unit icon at enemy wave spawn points
- **Auto drill placement** — select a drill, hover an ore patch, press **H**: the patch is flood-filled and covered with build plans placed greedily for maximum coverage (queued to your unit like a schematic — builds at normal speed)

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

Environment effects are removed by clearing `renderer.envRenderers` (saved and restored when toggled back). Map edge darkness is removed by flipping `rules.borderDarkness` client-side and recomputing darkness via `renderer.updateAllDarkness()`; the map's original value is restored when the toggle is turned off.

## Building

```sh
./gradlew jar
```

The jar appears in `build/libs/`. Requires JDK 17+ (if your system Java is newer than Gradle supports, set `org.gradle.java.home` in `gradle.properties`).

## License

[MIT](LICENSE)
