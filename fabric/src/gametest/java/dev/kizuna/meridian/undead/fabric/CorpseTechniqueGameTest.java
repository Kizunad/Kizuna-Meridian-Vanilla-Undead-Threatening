package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.undead.server.UndeadContent;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;

public final class CorpseTechniqueGameTest {
    private record Art(String id, int price, long warmup, long cooldown) { }
    private static final Art LEAP = new Art("meridian_undead:leap", 3, 600, 8000);
    private static final Art HIDE = new Art("meridian_undead:bronze_hide", 5, 500, 30000);
    private static final List<Art> ARTS = List.of(LEAP, HIDE);
    private static final String GUARD = "meridian_basic_techniques:guard";
    private static final String BRONZE_HIDE_ID = "meridian_undead:bronze_hide";
    private static final double BRONZE_HIDE_PROTECTED_SHARE = 0.6;
    private static final double BRONZE_HIDE_HALF_PEARL_SHARE = 0.3;
    private static final double BRONZE_HIDE_HALF_HP = 0.5;
    private static final double BRONZE_HIDE_SPEED_MULTIPLIER = 0.7;
    private static final double SPEED_TOLERANCE = 1e-9;
    private static final int BRONZE_HIDE_DURATION_TICKS = 200;
    private static final int BRONZE_HIDE_RESTORED_CHECK_TICKS = 1;
    private static final int BRONZE_HIDE_EXPIRED_CHECK_TICKS = 202;
    private static final int BRONZE_HIDE_LIFECYCLE_TEST_MAX_TICKS = 230;
    private static final int WALK_TICKS = 20;
    private static final int SETTLE_TICKS = 3;
    private static final double MOVEMENT_TOLERANCE = 0.08;
    private static final double WALK_SPEED = 1;
    private static final int WALK_LANE_LENGTH = 6;
    private static final int WALK_TARGET_Z = WALK_LANE_LENGTH;
    private static final float AUTO_CAST_TRIGGER_DOSE = 1.0F;
    private static final int AUTO_CAST_CHECK_TICKS = 30;

    @GameTest
    public void whiteAndLivingCannotLearnOrCastCorpseArts(GameTestHelper test) {
        // 同刻查询、学式和起式，拒绝后连身体版本与学习记录一并比较。
        var white = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.MORTAL);
        var living = test.spawn(EntityTypes.VILLAGER, SPAWN, SPAWN, SPAWN);
        for (var entity : List.<LivingEntity>of(white, living)) {
            var known = known(entity);
            var before = body(entity);
            test.assertTrue(known.contains(GUARD), "类人缺天生格挡");
            for (var art : ARTS) {
                test.assertTrue(!known.contains(art.id()), "不应列出 " + art.id());
                var service = TechniqueFabric.service();
                try {
                    service.learn(entity.getUUID(), art.id());
                    test.fail("学会了不适用的天生式 " + art.id());
                } catch (TechniqueService.InnateLockedException locked) {
                    test.assertTrue(entity == white, "活人未按模板拒绝");
                } catch (TechniqueService.BodyTemplateMismatchException mismatch) {
                    test.assertTrue(entity == living, "白僵模板应能绑定");
                }
                var reason = entity == white ? NOT_KNOWN : BODY_TEMPLATE_MISMATCH;
                rejected(test, entity, art, reason);
            }
            test.assertTrue(before.equals(body(entity)), "失败后身体改变");
        }
        test.succeed();
    }

    @GameTest
    public void unlockedArtsPayFromPearlOnce(GameTestHelper test) {
        // 每个道行、每一式都使用新实体，避免活动和冷却掩盖另一式的结果。
        for (var rank : List.of(HumanoidTier.WANDERING_QI, HumanoidTier.TRUE_QI)) {
            for (var art : ARTS) {
                var mob = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, rank);
                if (art == LEAP) {
                    CorpseLeapGameTest.prepareGround(test);
                    double groundY = test.absolutePos(new BlockPos(SPAWN, SPAWN, SPAWN)).getY();
                    mob.setPos(mob.getX(), groundY, mob.getZ());
                    mob.setOnGround(true /* onGround */);
                }
                var before = body(mob);
                test.assertTrue(known(mob).containsAll(List.of(GUARD, LEAP.id(), HIDE.id())), "天生式清单缺失");
                TechniqueFabric.service().learn(mob.getUUID(), art.id());
                test.assertTrue(before.equals(body(mob)), "重复学天生式写了身体");
                paidOnce(test, mob, before, art);
            }
        }
        test.succeed();
    }

    @GameTest(maxTicks = 40)
    public void realMeleeHitStartsBronzeHideOnlyWithLivingTarget(GameTestHelper test) {
        var target = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.WANDERING_QI);
        var victim = test.spawn(EntityTypes.VILLAGER, SPAWN, SPAWN, SPAWN);
        var attacker = test.spawn(EntityTypes.COW, SPAWN, SPAWN, SPAWN);
        target.setTarget(victim);
        target.invulnerableTime = 0;
        test.assertTrue(target.hurtServer(test.getLevel(), test.getLevel().damageSources().mobAttack(attacker),
                AUTO_CAST_TRIGGER_DOSE),
                "近战实打没有造成有效伤害");

        test.runAfterDelay(AUTO_CAST_CHECK_TICKS, () -> {
            var benefit = TechniqueStatusBenefits.get("meridian_undead:bronze_hide");
            test.assertTrue(target.hasEffect(benefit.effect()), "命中后未自动起铜皮");
            test.succeed();
        });
    }

    @GameTest(maxTicks = 40)
    public void realMeleeHitDoesNotStartBronzeHideWithoutTarget(GameTestHelper test) {
        var target = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.WANDERING_QI);
        var attacker = test.spawn(EntityTypes.COW, SPAWN, SPAWN, SPAWN);
        target.invulnerableTime = 0;
        test.assertTrue(target.hurtServer(test.getLevel(), test.getLevel().damageSources().mobAttack(attacker),
                AUTO_CAST_TRIGGER_DOSE),
                "近战实打没有造成有效伤害");

        test.runAfterDelay(AUTO_CAST_CHECK_TICKS, () -> {
            var benefit = TechniqueStatusBenefits.get("meridian_undead:bronze_hide");
            test.assertTrue(!target.hasEffect(benefit.effect()), "没有攻击目标时自动起了铜皮");
            test.succeed();
        });
    }

    @GameTest
    public void protectedMeleeHitDoesNotWriteHit(GameTestHelper test) {
        var target = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.WANDERING_QI);
        var benefit = TechniqueStatusBenefits.get(BRONZE_HIDE_ID);
        TechniqueStatusBenefits.apply(target, benefit.benefit());
        var attacker = test.spawn(EntityTypes.COW, SPAWN, SPAWN, SPAWN);
        target.invulnerableTime = 0;
        test.assertTrue(target.hurtServer(test.getLevel(), test.getLevel().damageSources().mobAttack(attacker),
                AUTO_CAST_TRIGGER_DOSE),
                "铜皮下近战实打没有造成有效伤害");
        test.assertTrue(TechniqueFabric.service().staggerRemainingMillis(target.getUUID()) == 0,
                "铜皮保护的近战实打写入了 HIT");
        test.succeed();
    }

    @GameTest
    public void bronzeHideProtectsOnlyDirectStrikesAndUndead(GameTestHelper test) {
        var corpse = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.WANDERING_QI);
        var benefit = TechniqueStatusBenefits.get(BRONZE_HIDE_ID);
        TechniqueStatusBenefits.apply(corpse, benefit.benefit());
        var attacker = test.spawn(EntityTypes.COW, SPAWN, SPAWN, SPAWN);
        var snapshot = body(corpse);
        DamageSource melee = corpse.damageSources().mobAttack(attacker);
        assertProtectedShare(test, UndeadFabric.protectedDamageShare(corpse, melee, snapshot),
                BRONZE_HIDE_PROTECTED_SHARE, "近战实打未受保护");

        var arrow = test.spawn(EntityTypes.ARROW, SPAWN, SPAWN, SPAWN);
        arrow.setOwner(attacker);
        DamageSource arrowSource = corpse.damageSources().arrow(arrow, attacker);
        assertProtectedShare(test, UndeadFabric.protectedDamageShare(corpse, arrowSource, snapshot),
                BRONZE_HIDE_PROTECTED_SHARE, "箭矢实打未受保护");

        var halfPearl = halfPearl(snapshot);
        assertProtectedShare(test, UndeadFabric.protectedDamageShare(corpse, melee, halfPearl),
                BRONZE_HIDE_HALF_PEARL_SHARE, "尸丹半血时保护份额未线性减半");
        assertProtectedShare(test, UndeadFabric.protectedDamageShare(corpse, melee, emptyPearl(snapshot)),
                0, "尸丹归零时仍有保护份额");

        assertUnprotected(test, corpse, corpse.damageSources().fall(), snapshot, "摔落");
        assertUnprotected(test, corpse, corpse.damageSources().inFire(), snapshot, "火");
        assertUnprotected(test, corpse, corpse.damageSources().magic(), snapshot, "魔法");

        var villager = test.spawn(EntityTypes.VILLAGER, SPAWN, SPAWN, SPAWN);
        TechniqueStatusBenefits.apply(villager, benefit.benefit());
        assertUnprotected(test, villager, villager.damageSources().mobAttack(attacker), body(villager), "生人");

        corpse.removeEffect(benefit.effect());
        assertUnprotected(test, corpse, melee, snapshot, "药效移除后");
        test.succeed();
    }

    @GameTest(maxTicks = BRONZE_HIDE_LIFECYCLE_TEST_MAX_TICKS)
    public void bronzeHideMovementSpeedClearsAndRestoresFromSave(GameTestHelper test) {
        var source = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.WANDERING_QI);
        var benefit = TechniqueStatusBenefits.get(BRONZE_HIDE_ID);
        double baseSpeed = movementSpeed(source);
        layWalkLane(test);
        double baseDistance = walkedBlocks(test, source);

        TechniqueStatusBenefits.apply(source, benefit.benefit());
        assertSpeed(test, source, baseSpeed, "铜皮没有降低移速");
        double slowedDistance = walkedBlocks(test, source);
        assertMovementMultiplier(test, baseDistance, slowedDistance);
        clearBronzeHide(test, source);
        test.assertTrue(Math.abs(movementSpeed(source) - baseSpeed) <= SPEED_TOLERANCE,
                "清除药效后移速修饰仍在");

        TechniqueStatusBenefits.apply(source, benefit.benefit());
        Zombie restored = restoredWithEntityData(test, source);
        test.runAfterDelay(BRONZE_HIDE_RESTORED_CHECK_TICKS, () -> {
            test.assertTrue(restored.hasEffect(benefit.effect()), "读档没有恢复铜皮时效");
            assertSpeed(test, restored, baseSpeed, "读档后移速修饰未恢复");
        });
        test.runAfterDelay(BRONZE_HIDE_EXPIRED_CHECK_TICKS, () -> {
            test.assertTrue(!restored.hasEffect(benefit.effect()), "铜皮超过十秒仍未到期");
            test.assertTrue(Math.abs(movementSpeed(restored) - baseSpeed) <= SPEED_TOLERANCE,
                    "铜皮到期后移速修饰仍在");
            test.succeed();
        });
    }

    private static void clearBronzeHide(GameTestHelper test, LivingEntity source) {
        int[] result = {0};
        var commandSource = test.getLevel().getServer().createCommandSourceStack()
                .withLevel(test.getLevel())
                .withPosition(source.position())
                .withCallback((success, value) -> result[0] = success ? value : 0);
        String command = "effect clear " + source.getStringUUID() + " " + BRONZE_HIDE_ID;
        test.getLevel().getServer().getCommands().performPrefixedCommand(commandSource, command);
        test.assertTrue(result[0] == 1, "effect clear 没有清掉铜皮");
    }

    private static void assertProtectedShare(GameTestHelper test, OptionalDouble actual, double expected,
                                             String message) {
        test.assertTrue(actual.isPresent()
                        && Math.abs(actual.getAsDouble() - expected) <= SPEED_TOLERANCE,
                message);
    }

    private static void assertUnprotected(GameTestHelper test, LivingEntity target, DamageSource source,
                                         BodySnapshot snapshot, String label) {
        test.assertTrue(UndeadFabric.protectedDamageShare(target, source, snapshot).isEmpty(),
                label + "伤害不应受铜皮保护");
    }

    private static BodySnapshot halfPearl(BodySnapshot snapshot) {
        var edited = snapshot.toBuilder();
        var pearl = edited.getOrganStatesBuilderList().stream()
                .filter(state -> state.getId().equals(UndeadContent.PEARL))
                .findFirst()
                .orElseThrow();
        pearl.setHp(pearl.getHp() * BRONZE_HIDE_HALF_HP);
        return edited.build();
    }

    private static BodySnapshot emptyPearl(BodySnapshot snapshot) {
        var edited = snapshot.toBuilder();
        var pearl = edited.getOrganStatesBuilderList().stream()
                .filter(state -> state.getId().equals(UndeadContent.PEARL))
                .findFirst()
                .orElseThrow();
        pearl.setHp(0);
        return edited.build();
    }

    private static double movementSpeed(LivingEntity entity) {
        return entity.getAttribute(Attributes.MOVEMENT_SPEED).getValue();
    }

    private static void assertSpeed(GameTestHelper test, LivingEntity entity, double baseSpeed, String message) {
        double expectedAttributeMultiplier = Math.sqrt(BRONZE_HIDE_SPEED_MULTIPLIER);
        double expectedSpeed = baseSpeed * expectedAttributeMultiplier;
        double actualSpeed = movementSpeed(entity);
        test.assertTrue(Math.abs(actualSpeed - expectedSpeed) <= SPEED_TOLERANCE,
                message + "，期望 " + expectedSpeed + "，实际 " + actualSpeed);
    }

    private static void assertMovementMultiplier(GameTestHelper test, double baseDistance, double slowedDistance) {
        test.assertTrue(baseDistance > SPEED_TOLERANCE, "基线移速没有实际位移");
        double actualMultiplier = slowedDistance / baseDistance;
        test.assertTrue(Math.abs(actualMultiplier - BRONZE_HIDE_SPEED_MULTIPLIER) <= MOVEMENT_TOLERANCE,
                "实际地面移速没有降到七成：" + actualMultiplier);
    }

    private static void layWalkLane(GameTestHelper test) {
        int firstLaneX = SPAWN - 1;
        int lastLaneX = SPAWN + 1;
        int floorY = SPAWN - 1;
        for (int z = 0; z <= WALK_TARGET_Z; z++) {
            for (int x = firstLaneX; x <= lastLaneX; x++) {
                BlockPos position = test.absolutePos(new BlockPos(x, floorY, z));
                test.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static double walkedBlocks(GameTestHelper test, Zombie mob) {
        Vec3 home = mob.position();
        float homeYaw = mob.getYRot();
        Vec3 start = settled(test, mob);
        Vec3 target = Vec3.atBottomCenterOf(test.absolutePos(new BlockPos(SPAWN, SPAWN, WALK_TARGET_Z)));
        for (int tick = 0; tick < WALK_TICKS; tick++) {
            mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, WALK_SPEED);
            mob.getMoveControl().tick();
            mob.travel(new Vec3(mob.xxa, mob.yya, mob.zza));
        }
        double walked = mob.position().subtract(start).horizontalDistance();
        mob.snapTo(home.x, home.y, home.z, homeYaw, mob.getXRot());
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setZza(0);
        return walked;
    }

    private static Vec3 settled(GameTestHelper test, Zombie mob) {
        Vec3 start = Vec3.atBottomCenterOf(test.absolutePos(new BlockPos(SPAWN, SPAWN, 0)));
        mob.snapTo(start.x, start.y, start.z, 0, 0);
        mob.setDeltaMovement(Vec3.ZERO);
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            mob.travel(Vec3.ZERO);
        }
        test.assertTrue(mob.onGround(), "铜皮移速夹具起点没有站在地上");
        return mob.position();
    }

    private static Zombie restoredWithEntityData(GameTestHelper test, Zombie source) {
        var saved = save(source);
        source.remove(RemovalReason.UNLOADED_TO_CHUNK);
        var restored = EntityTypes.HUSK.create(test.getLevel(), EntitySpawnReason.LOAD);
        load(restored, saved);
        test.getLevel().addFreshEntity(restored);
        return restored;
    }

    private static void paidOnce(GameTestHelper test, Zombie mob, BodySnapshot before, Art art) {
        // 由同一宿主的活动回执重放，再次核对完整快照，包括所有非付款账户。
        var service = TechniqueFabric.service();
        var request = request(before, art);
        var definition = service.registry().find(request.getTechnique()).orElseThrow();
        var snapshot = BodyProtobuf.fromMessage(before);
        var binding = TechniqueBinder.bind(snapshot, definition);
        test.assertTrue(binding.hasBinding() && TechniqueAvailability.affordable(snapshot, definition,
                binding.getBinding()), "预检未读到尸丹余额");
        var started = service.cast(mob.getUUID(), request);
        test.assertTrue(started.getFailure() == TECHNIQUE_FAILURE_UNSPECIFIED, "起式失败 " + started.getReason());
        var after = body(mob);
        var progress = TechniqueState.read(BodyProtobuf.fromMessage(after));
        var active = progress.getActiveCast();
        test.assertTrue(active.getWarmupEndMillis() - active.getStartedMillis() == art.warmup()
                && active.getCooldownMillis() == art.cooldown(), "起手或冷却错误 " + art.id());
        test.assertTrue(pearlAmount(before) - pearlAmount(after) == art.price(), "尸丹扣费错误 " + art.id());
        test.assertTrue(before.getMeridianStatesList().equals(after.getMeridianStatesList())
                && snapshot.totalQi() - BodyProtobuf.fromMessage(after).totalQi() == art.price(), "总账或经脉余额错误");
        var replay = service.cast(mob.getUUID(), request);
        test.assertTrue(replay.getReplayed() && replay.getRevision() == started.getRevision()
                && after.equals(body(mob)), "相同施放编号未复用回执或重复扣费");
    }

    @GameTest
    public void eachLimbAndTrunkGateKnowledge(GameTestHelper test) {
        // 保留另侧开脉；缺腿只影响纵，缺臂只影响铜皮，缺躯干两式都不能用。
        for (var id : List.of("ren", "left_stomach_meridian", "right_stomach_meridian",
                "left_lung_meridian", "right_lung_meridian")) {
            var mob = restored(test, snapshot -> snapshot.getMeridianStatesBuilderList().stream()
                    .filter(channel -> channel.getId().equals(id))
                    .findFirst().orElseThrow().getOpeningBuilder()
                    .setStage(MeridianOpeningStage.UNOPENED)
                    .setProgress(0));
            var listed = known(mob);
            for (var art : ARTS) {
                String limb = art == LEAP ? "stomach" : "lung";
                boolean required = id.equals("ren") || id.contains(limb);
                test.assertTrue(listed.contains(art.id()) != required, "解锁经脉错误 " + id + " " + art.id());
                if (required) {
                    rejected(test, mob, art, NOT_KNOWN);
                }
            }
        }
        test.succeed();
    }

    @GameTest
    public void emptyPearlCannotUseFundedMeridian(GameTestHelper test) {
        // 任脉有足额库存时，空丹仍应拒绝两式。
        assertUnpaid(test, Condition.EMPTY);
    }

    @GameTest
    public void brokenPearlCannotPay(GameTestHelper test) {
        // 碎丹仍保有库存，验证按器官自身完好度拒绝付款。
        assertUnpaid(test, Condition.BROKEN);
    }

    @GameTest
    public void disabledAbdomenCannotPay(GameTestHelper test) {
        // 尸丹有血有元，所属部位失能时仍不能付款。
        assertUnpaid(test, Condition.ABDOMEN);
    }

    private static void assertUnpaid(GameTestHelper test, Condition condition) {
        // 比较同一身体的清单、预检、真实起式及完整拒绝后快照。
        var mob = restored(test, snapshot -> preparePayer(snapshot, condition));
        var listed = known(mob);
        var before = body(mob);
        var snapshot = BodyProtobuf.fromMessage(before);
        for (var art : ARTS) {
            test.assertTrue(listed.contains(art.id()), "伤势或余额遮掉天生式");
            var definition = TechniqueFabric.service().registry().findLatest(art.id()).orElseThrow();
            var binding = TechniqueBinder.bind(snapshot, definition);
            if (condition == Condition.EMPTY) {
                test.assertTrue(binding.hasBinding() && !TechniqueAvailability.affordable(snapshot, definition,
                        binding.getBinding()), "空丹预检误用其他账户");
                rejected(test, mob, art, INSUFFICIENT_QI);
            } else {
                test.assertTrue(binding.getFailure() == MODULE_UNAVAILABLE, "伤势未阻止绑定 " + condition);
                rejected(test, mob, art, MODULE_UNAVAILABLE);
            }
        }
        test.assertTrue(before.equals(body(mob)), "起式失败改变身体 " + condition);
        test.succeed();
    }

    private enum Condition { EMPTY, BROKEN, ABDOMEN }

    private static void preparePayer(BodySnapshot.Builder body, Condition condition) {
        // 任脉取与出生尸丹相同的库存，确保足以单独覆盖任一式。
        var pearl = body.getOrganStatesBuilderList().stream().filter(o -> o.getId().equals(UndeadContent.PEARL))
                .findFirst().orElseThrow();
        var channels = body.getMeridianStatesBuilderList();
        channels.stream().filter(channel -> channel.getId().equals("ren")).findFirst().orElseThrow()
                .setLumenQi(pearl.getBuffer());
        switch (condition) {
            case EMPTY -> pearl.setBuffer(QiInventory.getDefaultInstance());
            case BROKEN -> pearl.setHp(0);
            case ABDOMEN -> body.getPartStatesBuilderList().stream().filter(part -> part.getId().equals("abdomen"))
                    .findFirst().orElseThrow().setHp(0);
        }
    }

    private static Zombie restored(GameTestHelper test, Consumer<BodySnapshot.Builder> edit) {
        // 经实体存读装入给定伤势、库存或开拓进度，再由正式宿主认领。
        var source = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.WANDERING_QI);
        var snapshot = body(source).toBuilder();
        edit.accept(snapshot);
        var saved = save(source);
        saved.getCompound(SAVE_KEY).orElseThrow().putByteArray("body", snapshot.build().toByteArray());
        source.remove(RemovalReason.UNLOADED_TO_CHUNK);
        var restored = EntityTypes.HUSK.create(test.getLevel(), EntitySpawnReason.LOAD);
        load(restored, saved);
        test.getLevel().addFreshEntity(restored);
        return restored;
    }

    private static List<String> known(LivingEntity entity) {
        // 从玩家和 AI 共用的服务端知识查询读出身份。
        return TechniqueFabric.service().known(entity.getUUID()).stream().map(entry -> entry.technique().getId())
                .toList();
    }

    static double pearlAmount(BodySnapshot body) {
        // 只统计尸丹缓存，独立于全身库存总量。
        var pearl = body.getOrganStatesList().stream().filter(organ -> organ.getId().equals(UndeadContent.PEARL))
                .findFirst().orElseThrow();
        return BodyProtobuf.fromInventory(pearl.getBuffer()).totalQi();
    }

    private static void rejected(GameTestHelper test, LivingEntity entity, Art art, TechniqueFailure failure) {
        // 错误码可区分身体范围、尚未解锁、缺元与伤势。
        var request = request(body(entity), art);
        var result = TechniqueFabric.service().cast(entity.getUUID(), request);
        test.assertTrue(result.getFailure() == failure, art.id() + " 应拒绝为 " + failure + "，实际 " + result);
    }

    private static CastRequest request(BodySnapshot body, Art art) {
        // 起式请求只指定当前身体与精确版本，付款槽由服务端绑定。
        var reference = TechniqueReference.newBuilder()
                .setId(art.id())
                .setVersion(1)
                .build();
        return CastRequest.newBuilder()
                .setBodyId(body.getId())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
    }
}
