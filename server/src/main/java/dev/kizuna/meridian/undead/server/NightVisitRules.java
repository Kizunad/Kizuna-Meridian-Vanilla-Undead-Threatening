package dev.kizuna.meridian.undead.server;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class NightVisitRules {
    private static final long DAY = 24_000;
    private static final long NIGHT = 13_000;
    private static final long DAWN = 23_000;
    public static final int ESCAPE_DISTANCE = 48;

    private NightVisitRules() { }

    public static boolean night(long time) {
        long phase = Math.floorMod(time, DAY);
        return phase >= NIGHT && phase < DAWN;
    }

    public static UUID visitor(UUID ticket) {
        String identity = "meridian_undead:night_visit/" + ticket;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}
