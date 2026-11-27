package dev.kizuna.meridian.undead.server;

import static org.junit.Assert.*;
import java.util.UUID;
import org.junit.Test;

public final class NightVisitRulesTest {
    private static final int DUSK = 13_000;
    private static final int DAWN = 23_000;
    private static final int DAY = 24_000;

    @Test
    public void nocturnalWindowAndIdentitySurviveDayChange() {
        assertFalse(NightVisitRules.night(DUSK - 1));
        assertTrue(NightVisitRules.night(DAY + DUSK));
        assertFalse(NightVisitRules.night(DAY + DAWN));
        var ticket = UUID.randomUUID();
        assertEquals(NightVisitRules.visitor(ticket), NightVisitRules.visitor(UUID.fromString(ticket.toString())));
        assertNotEquals(ticket, NightVisitRules.visitor(ticket));
    }
}
