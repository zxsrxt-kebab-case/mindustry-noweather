package noweather;

import arc.Core;
import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.mod.Mod;

public class NoWeatherMod extends Mod {

    public NoWeatherMod() {
        Log.info("[NoWeather] mod loaded");
    }

    @Override
    public void init() {
        applyShowWeather();

        // Weather sound volume follows opacity, so forcing it to 0 also silences rain/wind
        Events.run(EventType.Trigger.update, () -> {
            if (enabled() && Vars.state.isGame()) {
                Groups.weather.each(w -> w.opacity(0f));
            }
        });

        Events.on(EventType.ClientLoadEvent.class, e -> Core.app.post(() ->
            Vars.ui.settings.addCategory(
                Core.bundle.get("setting.noweather.category", "Weather"),
                Icon.waves,
                table -> table.checkPref("noweather-enabled", true,
                    value -> Core.settings.put("showweather", !value))
            )
        ));

        Log.info("[NoWeather] initialized, weather hidden: " + enabled());
    }

    private static boolean enabled() {
        // WeatherState.draw() checks "showweather" and skips all rendering when false
        return Core.settings.getBool("noweather-enabled", true);
    }

    private static void applyShowWeather() {
        Core.settings.put("showweather", !enabled());
    }
}
