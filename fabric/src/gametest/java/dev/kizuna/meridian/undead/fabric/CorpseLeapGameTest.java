package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.SPAWN;
import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.body;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeapAccess;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.vanilla.MuscleRules;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;

public final class CorpseLeapGameTest {
    private enum ActorAi { ENABLED, DISABLED }

    private static final String LEAP_ID = "meridian_undead:leap";
    private static final int VERSION = 1;
    private static final int WARMUP_TICKS = 12;
    private static final int AI_SUPPRESSION_DELAY_TICKS = 12;
    private static final int FLIGHT_CHECK_TICKS = 42;
    private static final int AI_CHECK_TICKS = 70;
    private static final int RECOVERY_TICKS = 20;
    private static final int TEST_TIMEOUT_TICKS = AI_CHECK_TICKS + 5;
    private static final double DAMAGE_MULTIPLIER = 1.2;
    private static final double POSITION_EPSILON = 0.35;
    private static final double MAXIMUM_LEAP_DISTANCE = 6;
    private static final int GROUND_MIN_X = SPAWN - 2;
    private static final int GROUND_MAX_X = SPAWN + 9;
    private static final int GROUND_MIN_Z = SPAWN - 2;
    private static final int GROUND_MAX_Z = SPAWN + 2;
    private static final int GROUND_AIR_HEIGHT = 8;

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void stationaryTargetAtFourBlocksIsStruck(GameTestHelper test) {
        assertStationaryTarget(test, 4);
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void stationaryTargetAtSixBlocksIsStruck(GameTestHelper test) {
        assertStationaryTarget(test, 6);
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void stationaryTargetAtSevenBlocksIsStruck(GameTestHelper test) {
        assertStationaryTarget(test, 7);
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void targetMovingAfterWarmupIsMissed(GameTestHelper test) {
        Zombie actor = actor(test, EntityTypes.HUSK, ActorAi.DISABLED);
        LivingEntity target = target(test, actor, 7, 0);
        double targetHealth = target.getHealth();
        double actorZ = actor.getZ();
        CastOutcome outcome = cast(test, actor);
        assertAccepted(test, outcome);
        test.runAfterDelay(WARMUP_TICKS / 2, () -> target.setPos(target.getX(), target.getY(), actorZ + 1));
        test.runAfterDelay(FLIGHT_CHECK_TICKS, () -> {
            test.assertTrue(target.getHealth() == targetHealth, "起手后横移仍被纵命中");
            test.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void lowCeilingRejectsBeforePayment(GameTestHelper test) {
        Zombie actor = actor(test, EntityTypes.HUSK, ActorAi.DISABLED);
        LivingEntity target = target(test, actor, 6, 0);
        BlockPos ceiling = new BlockPos(SPAWN, SPAWN + 2, SPAWN);
        test.setBlock(ceiling, Blocks.STONE);
        double targetHealth = target.getHealth();
        long bodyRevision = body(actor).getRevision();
        CastOutcome outcome = cast(test, actor);
        test.assertTrue(outcome.getFailure() == TechniqueFailure.EFFECT_REJECTED, "低顶未拒绝纵");
        test.assertTrue(body(actor).getRevision() == bodyRevision, "低顶拒绝错误改变了身体");
        test.runAfterDelay(FLIGHT_CHECK_TICKS, () -> {
            TechniqueLeapAccess access = (TechniqueLeapAccess) actor;
            test.assertTrue(target.getHealth() == targetHealth,
                    "低顶拒绝后仍启动飞行 actor=" + actor.position()
                            + " target=" + target.position()
                            + " health=" + target.getHealth() + "/" + targetHealth
                            + " noAi=" + actor.isNoAi()
                            + " ready=" + access.meridian$getLeapReadyTick()
                            + " attacker=" + ((Mob) target).getLastHurtByMob()
                            + " damageSource=" + target.getLastDamageSource()
                            + " targetOnGround=" + target.onGround()
                            + " targetMotion=" + target.getDeltaMovement());
            test.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void wallCollisionMissesAndStartsRecovery(GameTestHelper test) {
        Zombie actor = actor(test, EntityTypes.HUSK, ActorAi.DISABLED);
        LivingEntity target = target(test, actor, 6, 0);
        double targetHealth = target.getHealth();
        BlockPos wallBase = new BlockPos(SPAWN + 3, SPAWN + 1, SPAWN);
        test.setBlock(wallBase, Blocks.STONE);
        test.setBlock(wallBase.above(), Blocks.STONE);
        test.setBlock(wallBase.above(2), Blocks.STONE);
        CastOutcome outcome = cast(test, actor);
        assertAccepted(test, outcome);
        test.runAfterDelay(FLIGHT_CHECK_TICKS, () -> {
            TechniqueLeapAccess access = (TechniqueLeapAccess) actor;
            long readyTick = access.meridian$getLeapReadyTick();
            long currentTick = test.getLevel().getGameTime();
            test.assertTrue(target.getHealth() == targetHealth, "撞墙后错误命中目标");
            test.assertTrue(readyTick >= currentTick - RECOVERY_TICKS - 2,
                    "撞墙后没有进入恢复计时");
            test.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void oneContactChoosesOnlyOneLivingTarget(GameTestHelper test) {
        Zombie actor = actor(test, EntityTypes.HUSK, ActorAi.DISABLED);
        LivingEntity first = target(test, actor, 6, -0.2);
        LivingEntity second = target(test, actor, 6, 0.2);
        double firstHealth = first.getHealth();
        double secondHealth = second.getHealth();
        CastOutcome outcome = cast(test, actor);
        assertAccepted(test, outcome);
        test.runAfterDelay(FLIGHT_CHECK_TICKS, () -> {
            boolean firstHit = first.getHealth() < firstHealth;
            boolean secondHit = second.getHealth() < secondHealth;
            test.assertTrue(firstHit ^ secondHit, "一次接触没有只选择一个活体目标");
            test.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void damageInterruptCancelsWarmup(GameTestHelper test) {
        Zombie actor = actor(test, EntityTypes.HUSK, ActorAi.DISABLED);
        LivingEntity target = target(test, actor, 6, 0);
        double targetHealth = target.getHealth();
        double actorX = actor.getX();
        CastOutcome outcome = cast(test, actor);
        assertAccepted(test, outcome);
        test.runAfterDelay(4, () -> actor.hurtServer(test.getLevel(), actor.damageSources().generic(), 1));
        test.runAfterDelay(FLIGHT_CHECK_TICKS, () -> {
            test.assertTrue(target.getHealth() == targetHealth, "受击打断后仍命中目标");
            test.assertTrue(Math.abs(actor.getX() - actorX) < POSITION_EPSILON, "受击打断后仍起跳");
            test.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void unloadingCancelsPendingLeap(GameTestHelper test) {
        Zombie unloaded = actor(test, EntityTypes.HUSK, ActorAi.DISABLED);
        LivingEntity unloadedTarget = target(test, unloaded, 6, 0);
        double unloadedHealth = unloadedTarget.getHealth();
        CastOutcome unloadedCast = cast(test, unloaded);
        assertAccepted(test, unloadedCast);
        unloaded.remove(RemovalReason.UNLOADED_TO_CHUNK);
        test.assertTrue(unloaded.isRemoved(), "卸载调用没有移除实体");

        test.runAfterDelay(FLIGHT_CHECK_TICKS, () -> {
            TechniqueLeapAccess access = (TechniqueLeapAccess) unloaded;
            test.assertTrue(access.meridian$getLeap() == null, "卸载后轨迹仍保留");
            test.assertTrue(unloadedTarget.getHealth() == unloadedHealth, "卸载后仍执行纵接触");
            test.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void deathCancelsPendingLeap(GameTestHelper test) {
        Zombie dead = actor(test, EntityTypes.HUSK, ActorAi.DISABLED);
        LivingEntity deadTarget = target(test, dead, 6, 0);
        double deadHealth = deadTarget.getHealth();
        CastOutcome deadCast = cast(test, dead);
        assertAccepted(test, deadCast);
        dead.kill(test.getLevel());

        test.runAfterDelay(FLIGHT_CHECK_TICKS, () -> {
            test.assertTrue(deadTarget.getHealth() == deadHealth, "死亡后仍执行纵接触");
            test.succeed();
        });
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void drownedInWaterAndSunlitZombieAreRejected(GameTestHelper test) {
        Zombie drowned = actor(test, EntityTypes.DROWNED, ActorAi.DISABLED);
        BlockPos water = new BlockPos(SPAWN, SPAWN + 1, SPAWN);
        test.setBlock(water, Blocks.WATER);
        LivingEntity drownedTarget = target(test, drowned, 6, 0);
        CastOutcome waterOutcome = cast(test, drowned);
        test.assertTrue(waterOutcome.getFailure() == TechniqueFailure.EFFECT_REJECTED, "水中溺尸未拒绝纵");
        test.assertTrue(drownedTarget.getHealth() == drownedTarget.getMaxHealth(), "水中拒绝仍改了目标");

        test.setTime(6000);
        Zombie sunlit = actor(test, EntityTypes.ZOMBIE, ActorAi.DISABLED);
        LivingEntity sunlitTarget = target(test, sunlit, 6, 0);
        CastOutcome sunlightOutcome = cast(test, sunlit);
        test.assertTrue(sunlightOutcome.getFailure() == TechniqueFailure.EFFECT_REJECTED, "日晒僵尸未拒绝纵");
        test.assertTrue(sunlitTarget.getHealth() == sunlitTarget.getMaxHealth(), "日晒拒绝仍改了目标");
        test.succeed();
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void whiteCorpseUsesOnlyVanillaAttack(GameTestHelper test) {
        Zombie actor = actor(test, EntityTypes.HUSK, HumanoidTier.MORTAL, ActorAi.DISABLED);
        LivingEntity target = target(test, actor, 1, 0);
        CastOutcome outcome = cast(test, actor);
        test.assertTrue(outcome.getFailure() == TechniqueFailure.NOT_KNOWN, "白僵错误获得纵");
        double health = target.getHealth();
        test.assertTrue(actor.doHurtTarget(test.getLevel(), target), "白僵原版攻击未执行");
        test.assertTrue(target.getHealth() < health, "白僵原版攻击没有造成伤害");
        test.succeed();
    }

    @GameTest(maxTicks = TEST_TIMEOUT_TICKS)
    public void enabledAiUsesLeapAndStopsNavigation(GameTestHelper test) {
        Zombie actor = actor(test, EntityTypes.HUSK, ActorAi.ENABLED);
        LivingEntity target = target(test, actor, 6, 0);
        double targetHealth = target.getHealth();
        actor.getNavigation().moveTo(target, 1);
        actor.setTarget(target);
        test.runAfterDelay(WARMUP_TICKS, () -> {
            TechniqueLeapAccess access = (TechniqueLeapAccess) actor;
            test.assertTrue(actor.getNavigation().isDone(),
                    "纵起手期间导航未停止 target=" + actor.getTarget()
                            + " locked=" + access.meridian$directedActionsLocked()
                            + " pending=" + access.meridian$getLeapPending()
                            + " flight=" + access.meridian$getLeap()
                            + " pos=" + actor.position());
        });
        test.runAfterDelay(AI_CHECK_TICKS, () -> {
            test.assertTrue(target.getHealth() < targetHealth, "开启 AI 的尸身没有自动纵");
            test.succeed();
        });
    }

    private static Zombie actor(GameTestHelper test, EntityType<? extends Zombie> type, ActorAi ai) {
        return actor(test, type, HumanoidTier.WANDERING_QI, ai);
    }

    private static Zombie actor(GameTestHelper test, EntityType<? extends Zombie> type, HumanoidTier tier,
                                ActorAi ai) {
        prepareGround(test);
        Zombie mob = CorpseBirthGameTest.commanded(test, type, tier);
        double groundY = test.absolutePos(new BlockPos(SPAWN, SPAWN, SPAWN)).getY();
        mob.setPos(mob.getX(), groundY, mob.getZ());
        mob.setOnGround(true /* onGround */);
        if (ai == ActorAi.DISABLED) {
            mob.setNoAi(true /* noAi */);
        }
        return mob;
    }

    static void prepareGround(GameTestHelper test) {
        for (int x = GROUND_MIN_X; x <= GROUND_MAX_X; x++) {
            for (int z = GROUND_MIN_Z; z <= GROUND_MAX_Z; z++) {
                for (int y = SPAWN; y < SPAWN + GROUND_AIR_HEIGHT; y++) {
                    test.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
                test.setBlock(new BlockPos(x, SPAWN - 1, z), Blocks.STONE);
            }
        }
    }

    private static Mob target(GameTestHelper test, Zombie actor, double distance, double lateral) {
        Mob target = test.spawn(EntityTypes.COW, SPAWN, SPAWN, SPAWN);
        target.setNoAi(true /* noAi */);
        target.setPos(actor.getX() + distance, actor.getY(), actor.getZ() + lateral);
        actor.setTarget(target);
        return target;
    }

    private static void assertStationaryTarget(GameTestHelper test, double distance) {
        Zombie actor = actor(test, EntityTypes.HUSK, ActorAi.DISABLED);
        actor.setPos(actor.getX() - SPAWN, actor.getY(), actor.getZ());
        actor.setOnGround(true /* onGround */);
        LivingEntity target = target(test, actor, distance, 0);
        double targetHealth = target.getHealth();
        double actorX = actor.getX();
        double baseDamage = actor.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double muscleCoefficient = VanillaDamageFabric.estimateStrikeCoefficient(
                body(actor), actor, MuscleRules.Grip.MAIN_HAND);
        double expected = baseDamage * DAMAGE_MULTIPLIER * muscleCoefficient;
        CastOutcome outcome = cast(test, actor);
        assertAccepted(test, outcome);
        test.runAfterDelay(FLIGHT_CHECK_TICKS, () -> {
            double damage = targetHealth - target.getHealth();
            TechniqueLeapAccess access = (TechniqueLeapAccess) actor;
            var flight = access.meridian$getLeap();
            String flightTicks = flight == null ? "none" : Integer.toString(flight.ticks);
            String state = " originX=" + actorX
                    + " actor=" + actor.position()
                    + " target=" + target.position()
                    + " movedX=" + (actor.getX() - actorX)
                    + " damage=" + damage
                    + " expected=" + expected
                    + " onGround=" + actor.onGround()
                    + " flightTicks=" + flightTicks
                    + " launch=" + (flight == null ? "none" : flight.launch)
                    + " ready=" + access.meridian$getLeapReadyTick();
            test.assertTrue(damage > 0, "站定目标未被纵命中，距离 " + distance + state);
            test.assertTrue(Math.abs(damage - expected) < POSITION_EPSILON, "纵伤害倍率错误" + state);
            double expectedX = actorX + Math.min(distance, MAXIMUM_LEAP_DISTANCE);
            test.assertTrue(actor.getX() > expectedX - POSITION_EPSILON,
                    "纵没有到达冻结方向落点，距离 " + distance);
            test.succeed();
        });
    }

    private static CastOutcome cast(GameTestHelper test, Zombie actor) {
        CastRequest request = CastRequest.newBuilder()
                .setBodyId(body(actor).getId())
                .setTechnique(TechniqueReference.newBuilder()
                        .setId(LEAP_ID)
                        .setVersion(VERSION)
                        .build())
                .setCastId(UUID.randomUUID().toString())
                .build();
        CastOutcome outcome = TechniqueFabric.service().cast(actor.getUUID(), request);
        actor.setTarget(null);
        actor.getNavigation().stop();
        if (outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            actor.setNoAi(false /* noAi */);
            test.runAfterDelay(WARMUP_TICKS + AI_SUPPRESSION_DELAY_TICKS,
                    () -> actor.setNoAi(true /* noAi */));
        }
        return outcome;
    }

    private static void assertAccepted(GameTestHelper test, CastOutcome outcome) {
        test.assertTrue(outcome.getFailure() == TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED,
                "纵起手失败 " + outcome);
    }
}
