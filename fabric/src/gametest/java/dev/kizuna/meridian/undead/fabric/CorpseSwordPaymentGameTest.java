package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;
import static dev.kizuna.meridian.undead.fabric.CorpseTechniqueGameTest.pearlAmount;

import dev.kizuna.meridian.humanoid.fabric.HumanoidSwordForms;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiPhase;
import dev.kizuna.meridian.protocol.pb.BodyMessages.QiInventory;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.undead.server.UndeadContent;
import java.nio.file.Files;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.Zombie.ZombieGroupData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public final class CorpseSwordPaymentGameTest {
    private static final String WIND = "meridian_piglin_sword:wind_around";
    private static final String SPLASH = "meridian_piglin_sword:gold_splash";
    private static final String HOT = "meridian_piglin_sword:molten";
    private static final String COATING = "meridian_piglin_sword:gold_caked";
    private static final double DYING = 0.25;
    private static final int SPLASH_PRICE = 4;
    private static final int WIND_PRICE = 3;
    private static final int AI_TIMEOUT = 160;
    private static final int MELEE_RANGE = 2;
    private static final BlockPos START = new BlockPos(SPAWN, SPAWN, SPAWN);

    @GameTest(maxTicks = AI_TIMEOUT)
    public void blackCorpsePaysWindFromPearl(GameTestHelper test) throws Exception {
        assertNaturalPayment(test, HumanoidTier.WANDERING_QI, CorpseBirthGameTest.BLACK_QI);
    }

    @GameTest(maxTicks = AI_TIMEOUT)
    public void hairyCorpsePaysWindFromPearl(GameTestHelper test) throws Exception {
        assertNaturalPayment(test, HumanoidTier.TRUE_QI, CorpseBirthGameTest.HAIRY_QI);
    }

    private static void assertNaturalPayment(GameTestHelper test, HumanoidTier tier, int birthQi) throws Exception {
        var corpse = naturalSwordsman(test, tier);
        knownWind(test, corpse);
        var target = shieldTarget(test, corpse);
        test.succeedWhen(() -> {
            var cast = TechniqueFabric.service().observe(corpse.getUUID()).flatMap(TechniqueService.Observation::cast);
            var after = body(corpse);
            double balance = pearlAmount(after);
            test.assertTrue(cast.isPresent(), tier + " 对举盾目标未起绕，尸丹余 " + balance);
            var request = cast.orElseThrow().active().getRequest();
            test.assertTrue(request.getTechnique().getId().equals(WIND)
                    && request.getTargetId().equals(target.getStringUUID()), "实际起式或目标错误 " + request);
            test.assertTrue(balance == birthQi - WIND_PRICE, "绕应从尸丹扣三元，起手后余 " + balance);
            assertEmptyChannels(test, after);
        });
    }

    @GameTest(maxTicks = AI_TIMEOUT)
    public void emptyPearlRejectsWind(GameTestHelper test) throws Exception {
        var original = naturalSwordsman(test, HumanoidTier.TRUE_QI);
        var corpse = restoreWithoutPearlQi(test, original);
        var reference = knownWind(test, corpse);
        var target = shieldTarget(test, corpse);
        test.succeedWhen(() -> {
            test.assertTrue(target.isBlocking() && corpse.getTarget() == target, "目标未就位举盾");
            var before = body(corpse);
            test.assertTrue(pearlAmount(before) == 0, "夹具尸丹尚有余元");
            test.assertFalse(HumanoidSwordForms.tryCast(corpse), "空丹尸身已由 AI 起式");
            var request = CastRequest.newBuilder()
                    .setBodyId(before.getId())
                    .setTechnique(reference)
                    .setTargetId(target.getStringUUID())
                    .setCastId(UUID.randomUUID().toString())
                    .build();
            var reply = TechniqueFabric.service().cast(corpse.getUUID(), request);
            test.assertTrue(reply.getFailure() == TechniqueFailure.INSUFFICIENT_QI,
                    "空丹拒绝原因错误 " + reply);
            test.assertTrue(body(corpse).equals(before), "拒绝施放后身体、活动或冷却改变");
        });
    }

    @GameTest(maxTicks = AI_TIMEOUT)
    public void dyingCorpsePaysSplashFromGasPearl(GameTestHelper test) throws Exception {
        var original = naturalSwordsman(test, HumanoidTier.TRUE_QI);
        var snapshot = body(original).toBuilder();
        var pearl = snapshot.getOrganStatesBuilderList().stream()
                .filter(organ -> organ.getId().equals(UndeadContent.PEARL))
                .findFirst().orElseThrow();
        // 出生存的是游离态。只把已有库存调到气态，数量和空经脉均沿用存档。
        var gas = QiDensity.baseline(QiPhase.GAS);
        pearl.getBufferBuilder().getEntriesBuilderList().forEach(entry -> entry.setDensity(gas));
        wound(snapshot);
        var corpse = restore(test, original, snapshot.build());
        double before = pearlAmount(body(corpse));
        var target = shieldTarget(test, corpse);
        corpse.setYRot(0);
        TechniqueStatusBenefits.apply(corpse, TechniqueStatusBenefits.get(HOT).benefit());
        test.assertTrue(HumanoidSwordForms.tryCast(corpse), "濒死尸修未起式");
        var cast = TechniqueFabric.service().observe(corpse.getUUID()).orElseThrow().cast().orElseThrow();
        test.assertTrue(cast.active().getRequest().getTechnique().getId().equals(SPLASH), "尸修未优先泼金");
        var paid = body(corpse);
        double after = pearlAmount(paid);
        test.assertTrue(after == before - SPLASH_PRICE, "尸丹应当扣四元，实际余 " + after);
        assertEmptyChannels(test, paid);
        test.succeedWhen(() -> {
            test.assertTrue(corpse.getMainHandItem().isEmpty(), "尸修的热剑尚未泼出");
            test.assertTrue(target.hasEffect(TechniqueStatusBenefits.get(COATING).effect()), "尸修扇内目标未挂金");
        });
    }

    @GameTest
    public void dyingCorpseWithFreeQiCannotPaySplash(GameTestHelper test) throws Exception {
        var original = naturalSwordsman(test, HumanoidTier.TRUE_QI);
        var snapshot = body(original).toBuilder();
        wound(snapshot);
        var corpse = restore(test, original, snapshot.build());
        var target = shieldTarget(test, corpse);
        corpse.setYRot(0);
        TechniqueStatusBenefits.apply(corpse, TechniqueStatusBenefits.get(HOT).benefit());
        test.assertTrue(HumanoidSwordForms.tryCast(corpse), "有游离真元且对手举盾应能起绕");
        var cast = TechniqueFabric.service().observe(corpse.getUUID()).orElseThrow().cast().orElseThrow();
        var request = cast.active().getRequest();
        test.assertTrue(!request.getTechnique().getId().equals(SPLASH)
                && request.getTargetId().equals(target.getStringUUID()), "游离态尸丹不应能付泼金费");
        test.succeed();
    }

    private static void wound(BodySnapshot.Builder healthy) {
        // 从原生满血快照设置各部位相同的剩余比例。
        healthy.getPartStatesBuilderList().forEach(part -> part.setHp(part.getHp() * DYING));
    }

    private static Zombie naturalSwordsman(GameTestHelper test, HumanoidTier tier) throws Exception {
        // 固定自然生成档位，在世界认领身体前备好金剑；经脉与尸丹使用正式出生库存。
        var config = Files.createTempFile("corpse-sword-birth-", ".toml");
        try {
            CorpseBirthGameTest.force(config, tier);
            var level = test.getLevel();
            var spawn = test.absolutePos(START);
            var corpse = EntityTypes.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.NATURAL);
            corpse.setPos(Vec3.atCenterOf(spawn));
            var adult = new ZombieGroupData(false /* isBaby */, false /* canSpawnJockey */);
            corpse.finalizeSpawn(level, level.getCurrentDifficultyAt(spawn), EntitySpawnReason.NATURAL, adult);
            corpse.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_SWORD));
            corpse.setNoGravity(true /* noGravity */);
            level.addFreshEntity(corpse);
            CorpseBirthGameTest.assertBirthRank(test, body(corpse), tier);
            return corpse;
        } finally {
            UndeadFabric.reloadConfig(CorpseBirthGameTest.CONFIG);
            Files.deleteIfExists(config);
        }
    }

    private static Zombie restoreWithoutPearlQi(GameTestHelper test, Zombie original) {
        // 只清除存档里的尸丹缓存，保留身体、兵器和已学的卷。
        var snapshot = body(original).toBuilder();
        snapshot.getOrganStatesBuilderList().stream().filter(organ -> organ.getId().equals(UndeadContent.PEARL))
                .findFirst().orElseThrow().setBuffer(QiInventory.getDefaultInstance());
        return restore(test, original, snapshot.build());
    }

    private static Zombie restore(GameTestHelper test, Zombie original, BodySnapshot snapshot) {
        var saved = save(original);
        saved.getCompound(SAVE_KEY).orElseThrow().putByteArray("body", snapshot.toByteArray());
        original.remove(RemovalReason.UNLOADED_TO_CHUNK);
        var level = test.getLevel();
        var restored = EntityTypes.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.LOAD);
        load(restored, saved);
        level.addFreshEntity(restored);
        return restored;
    }

    private static TechniqueReference knownWind(GameTestHelper test, Mob corpse) {
        var wind = TechniqueFabric.service().known(corpse.getUUID()).stream()
                .map(TechniqueService.KnownEntry::technique)
                .filter(reference -> reference.getId().equals(WIND))
                .findFirst();
        test.assertTrue(corpse.getMainHandItem().is(Items.GOLDEN_SWORD) && wind.isPresent(),
                "出生未持金剑或未学绕");
        return wind.orElseThrow();
    }

    private static Mob shieldTarget(GameTestHelper test, Mob corpse) {
        corpse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        var target = test.spawn(EntityTypes.VINDICATOR, START);
        target.setNoAi(true /* noAi */);
        target.setNoGravity(true /* noGravity */);
        target.setPos(corpse.position().add(0, 0, MELEE_RANGE));
        target.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        target.startUsingItem(InteractionHand.OFF_HAND);
        corpse.setTarget(target);
        return target;
    }

    private static void assertEmptyChannels(GameTestHelper test, BodySnapshot body) {
        for (var channel : body.getMeridianStatesList()) {
            test.assertTrue(channel.getLumenQi().getEntriesCount() == 0 && channel.getWallQi().getEntriesCount() == 0,
                    "剑式起手改变经脉库存 " + channel.getId());
        }
    }
}
