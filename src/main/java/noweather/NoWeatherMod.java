package noweather;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Renderer;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.mod.Mod;

public class NoWeatherMod extends Mod {
    private final Seq<Renderer.EnvRenderer> savedEnvRenderers = new Seq<>();
    private boolean mapBorderDarkness = true;

    public NoWeatherMod() {
        Log.info("[NoWeather] mod loaded");
    }

    @Override
    public void init() {
        applyShowWeather();

        // Weather sound volume follows opacity, so forcing it to 0 also silences rain/wind
        Events.run(EventType.Trigger.update, () -> {
            if (hideWeather() && Vars.state.isGame()) {
                Groups.weather.each(w -> w.opacity(0f));
            }
        });

        // Rules come from the map/server on every world load — remember the original
        // borderDarkness so the toggle can restore it
        Events.on(EventType.WorldLoadEvent.class, e -> Core.app.post(() -> {
            mapBorderDarkness = Vars.state.rules.borderDarkness;
            applyBorderDarkness();
        }));

        Events.on(EventType.ClientLoadEvent.class, e -> Core.app.post(() -> {
            applyEnvRenderers();
            Vars.ui.settings.addCategory(
                Core.bundle.get("setting.noweather.category", "Visual tweaks"),
                Icon.waves,
                table -> {
                    table.checkPref("noweather-enabled", true,
                        value -> Core.settings.put("showweather", !value));
                    table.checkPref("noweather-noenv", true,
                        value -> applyEnvRenderers());
                    table.checkPref("noweather-nodarkness", true,
                        value -> applyBorderDarkness());
                }
            );
        }));

        Log.info("[NoWeather] initialized, weather hidden: " + hideWeather());
    }

    private static boolean hideWeather() {
        // WeatherState.draw() checks "showweather" and skips all rendering when false
        return Core.settings.getBool("noweather-enabled", true);
    }

    private static void applyShowWeather() {
        Core.settings.put("showweather", !hideWeather());
    }

    private void applyEnvRenderers() {
        boolean hide = Core.settings.getBool("noweather-noenv", true);
        Seq<Renderer.EnvRenderer> current = Vars.renderer.envRenderers;
        if (hide && !current.isEmpty()) {
            savedEnvRenderers.clear();
            savedEnvRenderers.addAll(current);
            current.clear();
        } else if (!hide && current.isEmpty() && !savedEnvRenderers.isEmpty()) {
            current.addAll(savedEnvRenderers);
            savedEnvRenderers.clear();
        }
    }

    private void applyBorderDarkness() {
        if (!Vars.state.isGame()) return;
        boolean hide = Core.settings.getBool("noweather-nodarkness", true);
        boolean target = !hide && mapBorderDarkness;
        if (Vars.state.rules.borderDarkness != target) {
            Vars.state.rules.borderDarkness = target;
            Vars.renderer.updateAllDarkness();
        }
    }
}
