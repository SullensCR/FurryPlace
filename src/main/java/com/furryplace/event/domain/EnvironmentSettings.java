package com.furryplace.event.domain;

import org.bukkit.NamespacedKey;

public final class EnvironmentSettings {
    public enum WeatherChoice { CLEAR, RAIN, THUNDER }
    public enum TimeChoice {
        DAWN(23_000L), DAY(1_000L), NOON(6_000L), SUNSET(12_000L), NIGHT(13_000L), MIDNIGHT(18_000L);

        private final long ticks;

        TimeChoice(long ticks) {
            this.ticks = ticks;
        }

        public long ticks() {
            return ticks;
        }
    }

    private WeatherChoice weather;
    private TimeChoice time;
    private NamespacedKey biome;

    public WeatherChoice weather() {
        return weather;
    }

    public void weather(WeatherChoice weather) {
        this.weather = weather;
    }

    public TimeChoice time() {
        return time;
    }

    public void time(TimeChoice time) {
        this.time = time;
    }

    public NamespacedKey biome() {
        return biome;
    }

    public void biome(NamespacedKey biome) {
        this.biome = biome;
    }
}

