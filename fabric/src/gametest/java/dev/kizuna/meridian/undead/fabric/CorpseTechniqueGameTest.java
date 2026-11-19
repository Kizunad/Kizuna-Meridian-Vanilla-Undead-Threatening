package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.undead.server.UndeadContent;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;
import static dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure.*;

public final class CorpseTechniqueGameTest {
    private record Art(String id, int price, long warmup, long cooldown) { }
    private static final Art LEAP = new Art("meridian_undead:leap", 3, 600, 8000);
    private static final Art HIDE = new Art("meridian_undead:bronze_hide", 5, 500, 30000);
    private static final List<Art> ARTS = List.of(LEAP, HIDE);
    private static final String GUARD = "meridian_basic_techniques:guard";

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
                var before = body(mob);
                test.assertTrue(known(mob).containsAll(List.of(GUARD, LEAP.id(), HIDE.id())), "天生式清单缺失");
                TechniqueFabric.service().learn(mob.getUUID(), art.id());
                test.assertTrue(before.equals(body(mob)), "重复学天生式写了身体");
                paidOnce(test, mob, before, art);
            }
        }
        test.succeed();
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

    private static double pearlAmount(BodySnapshot body) {
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
