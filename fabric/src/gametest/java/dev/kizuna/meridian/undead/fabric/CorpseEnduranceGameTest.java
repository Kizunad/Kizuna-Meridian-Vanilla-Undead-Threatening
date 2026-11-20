package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.healing.fabric.HealingFabric;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueState;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.undead.server.UndeadContent;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;

public final class CorpseEnduranceGameTest {
    private static final int DEFAULT_RATIO = 2;
    private static final int HIGH_RATIO = 3;
    private static final long REST_MILLIS = 30_000;
    private static final int REST_TICKS = 10;
    private static final double EPSILON = 0.00001;

    @GameTest
    public void adultBodiesFollowHumanMusclesAndLifespan(GameTestHelper test) throws Exception {
        var path = Files.createTempFile("corpse-capacity-", ".toml");
        try {
            for (int factor : new int[]{1, DEFAULT_RATIO, HIGH_RATIO}) {
                configure(path, factor, 1);
                for (var type : CorpseSpeciesGameTest.TYPES) {
                    var mob = spawn(test, type);
                    assertBody(test, body(mob), factor);
                    mob.discard();
                }
            }
        } finally {
            UndeadFabric.reloadConfig(CorpseBirthGameTest.CONFIG);
            Files.deleteIfExists(path);
        }
        test.succeed();
    }

    private static void assertBody(GameTestHelper test, BodySnapshot body, int factor) {
        var human = HumanContent.bundle().getTemplates(0).getStructure();
        double humanLife = HumanBirth.LIFESPAN_YEARS * HumanBirth.SECONDS_PER_YEAR;
        test.assertTrue(body.getVitality().getLifespanLimitSeconds() == humanLife * factor, "寿命上限错误");
        var structure = body.getStructure();
        for (int i = 0; i < human.getTissuesCount(); i++) {
            var original = human.getTissues(i);
            var expected = original.toBuilder();
            var capacity = original.getCapacity();
            if (original.getKind().equals("human:muscle")) {
                expected.getCapacityBuilder().setEnduranceJoules(capacity.getEnduranceJoules() * factor);
            } else if (original.getId().equals("attention_network")) {
                expected.getCapacityBuilder().setEnduranceJoules(capacity.getEnduranceJoules() * MENTAL_RATIO);
            }
            test.assertTrue(structure.getTissues(i).equals(expected.build()), "组织构型错误 " + original.getId());
        }
        var brain = human.getOrgansList().stream().filter(o -> o.getKind().equals("human:brain"))
                .findFirst().orElseThrow();
        var actual = structure.getOrgansList().stream().filter(o -> o.getId().equals(brain.getId()))
                .findFirst().orElseThrow();
        test.assertTrue(actual.getEndurance().getEnduranceJoules() == brain.getEndurance().getEnduranceJoules()
                * MENTAL_RATIO, "脑耐力错误");
    }

    private static final double MENTAL_RATIO = 0.2;

    @GameTest
    public void meleeSpendsMuscleEnduranceAndRestRecoversIt(GameTestHelper test) throws Exception {
        var path = Files.createTempFile("corpse-recovery-", ".toml");
        try {
            configure(path, 1, 1);
            double humanScale = strikeAndRest(test, DEFAULT_RATIO);
            configure(path, DEFAULT_RATIO, 1);
            double corpseScale = strikeAndRest(test, DEFAULT_RATIO);
            test.assertTrue(Math.abs(humanScale - DEFAULT_RATIO * corpseScale) < EPSILON, "倍耐力出手疲劳错误");
            configure(path, DEFAULT_RATIO, 0);
            strikeAndRest(test, 1);
        } finally {
            UndeadFabric.reloadConfig(CorpseBirthGameTest.CONFIG);
            Files.deleteIfExists(path);
        }
        test.succeed();
    }

    private static double strikeAndRest(GameTestHelper test, int divisor) {
        var mob = spawn(test, EntityTypes.HUSK);
        var target = test.spawn(EntityTypes.COW, SPAWN, SPAWN, SPAWN);
        var before = body(mob);
        test.assertTrue(fatigue(before) == 0, "出生已有疲劳");
        mob.doHurtTarget(test.getLevel(), target);
        var worked = body(mob);
        double spent = fatigue(worked);
        test.assertTrue(spent > 0, "原版尸壳近战没有组织做功");
        var current = BodyProtobuf.fromMessage(worked);
        var now = current.settledAt();
        var end = new TimeCursor(now.domain(), now.epoch(), now.elapsedMillis() + REST_MILLIS);
        var plan = new BodyTimeAdvancement.Plan(end, 1, BodyTimeAdvancement.Mode.ACTIVE);
        var rested = BodyProtobuf.toMessage(BodyTimeAdvancement.apply(current, plan).body());
        test.assertTrue(Math.abs(fatigue(rested) - spent / divisor) < EPSILON, "静养后的肌肉疲劳错误");
        target.discard();
        mob.discard();
        return spent;
    }

    private static double fatigue(BodySnapshot body) {
        return body.getTissueStatesList().stream().mapToDouble(TissueState::getFatigue).sum();
    }

    private static void configure(Path path, int factor, int recovery) throws Exception {
        Files.writeString(path, "muscle_endurance_multiplier = " + factor + "\nlifespan_multiplier = " + factor
                + "\nmuscle_recovery_multiplier = " + recovery + "\nnatural_healing_multiplier = " + recovery);
        UndeadFabric.reloadConfig(path);
    }

    static void rest(GameTestHelper test, Zombie mob, double expectedGain) {
        // 实体刻驱动回血；身体部位和投影红心各自核对。
        HealingFabric.refresh(mob);
        var before = body(mob);
        double total = before.getPartStatesList().stream().mapToDouble(PartState::getHp).sum();
        for (int i = 0; i < REST_TICKS; i++) {
            mob.tick();
        }
        var after = body(mob);
        double hp = after.getPartStatesList().stream().mapToDouble(PartState::getHp).sum();
        double max = after.getStructure().getPartsList().stream().mapToDouble(p -> p.getMaxHp()).sum();
        test.assertTrue(Math.abs(hp - total - expectedGain) < EPSILON, "静养治疗剂量错误 " + (hp - total));
        test.assertTrue(Math.abs(mob.getHealth() - hp / max * mob.getMaxHealth()) < EPSILON, "红心与身体不一致");
        test.assertTrue(before.getOrganStatesList().equals(after.getOrganStatesList()), "治疗改写了器官");
    }

    @GameTest
    public void woundedCorpseHealsWhileResting(GameTestHelper test) throws Exception {
        var path = Files.createTempFile("corpse-healing-", ".toml");
        var mob = spawn(test, EntityTypes.HUSK);
        mob.setNoAi(true /* noAi */);
        try {
            var broken = damage(mob, organ(UndeadContent.PEARL), LETHAL_HP, DamageKind.CUSTOM);
            var pearl = broken.getOrganStatesList().stream().filter(o -> o.getId().equals(UndeadContent.PEARL))
                    .findFirst().orElseThrow();
            test.assertTrue(pearl.getHp() == 0 && !broken.getVitality().getDead(), "碎丹夹具没有得到活尸");
            damage(mob, part("abdomen"), WOUND_HP, DamageKind.CUSTOM);
            configure(path, DEFAULT_RATIO, 0);
            rest(test, mob, 0);
            configure(path, DEFAULT_RATIO, 1);
            rest(test, mob, HEALTHY_DOSE);
            damage(mob, part("head"), LETHAL_HP, DamageKind.CUSTOM);
            HealingFabric.refresh(mob);
            test.assertTrue(!mob.isAlive(), "致命伤未死亡");
            for (int i = 0; i < REST_TICKS; i++) {
                mob.tick();
            }
            test.assertTrue(!mob.isAlive() && mob.getHealth() == 0, "死体自然回血");
        } finally {
            UndeadFabric.reloadConfig(CorpseBirthGameTest.CONFIG);
            Files.deleteIfExists(path);
        }
        test.succeed();
    }

    static final double HEALTHY_DOSE = 0.5;
    static final double WOUND_HP = 4;
    private static final double LETHAL_HP = 100;
}
