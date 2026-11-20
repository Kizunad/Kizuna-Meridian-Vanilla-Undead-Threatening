package dev.kizuna.meridian.undead.server;

import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TimeCursor;
import java.util.stream.Collectors;
import org.junit.Test;

import static org.junit.Assert.*;

public final class CorpseBodyTest {
    private static final double DEFAULT_LIFESPAN_RATIO = 2;

    @Test
    public void birthProvidesEveryOrganBeforeHostValidation() {
        // 宿主在绑定时会拒绝残缺快照。在绑定前逐个比对能指出具体缺少的器官。
        var template = UndeadContent.bundle().getTemplates(0);
        var now = TimeCursor.newBuilder()
                .setDomain("meridian_undead:test")
                .setEpoch("birth")
                .setElapsedMillis(1)
                .build();
        var born = CorpseBody.born(template, "test_corpse", now, CorpseRank.WHITE);
        var organs = born.getOrganStatesList().stream().collect(Collectors.toMap(OrganState::getId, state -> state));
        for (var definition : template.getStructure().getOrgansList()) {
            var state = organs.get(definition.getId());
            assertNotNull("出生缺少器官 " + definition.getId(), state);
            assertEquals(definition.getMaxHp(), state.getHp(), 0);
            assertTrue(state.hasBuffer());
        }
        var validated = BodyProtobuf.fromMessage(born);
        assertEquals("test_corpse", validated.id().value());
        assertEquals(now, born.getSettledAt());
        double humanLifespan = HumanBirth.LIFESPAN_YEARS * HumanBirth.SECONDS_PER_YEAR;
        assertEquals(humanLifespan * DEFAULT_LIFESPAN_RATIO, born.getVitality().getLifespanLimitSeconds(), 0);
        assertEquals(0, validated.totalQi(), 0);
    }
}
