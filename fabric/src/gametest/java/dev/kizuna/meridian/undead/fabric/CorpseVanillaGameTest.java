package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;

import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;

public final class CorpseVanillaGameTest {
    private static final int WOUND = 8;
    private static final int FALL_HEIGHT = 8;
    private static final int NOON = 6000;
    private static final int SUN_TICKS = 100;
    private static final int TIMEOUT = 140;
    private static final double EPSILON = 0.0001;

    @GameTest
    public void invertedPotionsWitherAndFallAffectTheBody(GameTestHelper test) {
        var zombie = spawn(test, EntityTypes.HUSK);
        double healthy = hp(zombie);
        var level = test.getLevel();
        MobEffects.INSTANT_HEALTH.value().applyInstantaneousEffect(level, null, null, zombie, 0, 1);
        double afterHarm = hp(zombie);
        test.assertTrue(afterHarm < healthy, "瞬间治疗没有伤到尸身");
        MobEffects.INSTANT_DAMAGE.value().applyInstantaneousEffect(level, null, null, zombie, 0, 1);
        double afterHeal = hp(zombie);
        test.assertTrue(afterHeal > afterHarm, "瞬间伤害没有治伤");
        zombie.invulnerableTime = 0;
        MobEffects.WITHER.value().applyEffectTick(level, zombie, 0);
        double afterWither = hp(zombie);
        test.assertTrue(afterWither < afterHeal, "凋零的持续伤害没有落到部位");
        zombie.invulnerableTime = 0;
        zombie.causeFallDamage(FALL_HEIGHT, 1, zombie.damageSources().fall());
        double afterFall = hp(zombie);
        test.assertTrue(afterFall < afterWither, "摔落没有伤到部位");
        double projected = zombie.getMaxHealth() * afterFall / healthy;
        test.assertTrue(Math.abs(zombie.getHealth() - projected) < EPSILON && zombie.isAlive(),
                "原版血条投影不符");
        test.succeed();
    }

    @GameTest(maxTicks = TIMEOUT)
    public void sunFireHurtsZombieButSpareHusk(GameTestHelper test) {
        test.setTime(NOON);
        var zombie = spawn(test, EntityTypes.ZOMBIE);
        var husk = spawn(test, EntityTypes.HUSK);
        zombie.setNoAi(true /* noAi */);
        husk.setNoAi(true /* noAi */);
        double healthy = hp(zombie);
        test.runAfterDelay(SUN_TICKS, () -> {
            test.assertTrue(zombie.isOnFire() && hp(zombie) < healthy, "日晒未点燃僵尸并伤及尸身");
            test.assertTrue(!husk.isOnFire() && hp(husk) == healthy, "尸壳受到日晒火伤");
            test.succeed();
        });
    }

    @GameTest
    public void drownedAndHuskConversionReleaseOldBody(GameTestHelper test) {
        checkConversion(test, EntityTypes.ZOMBIE, EntityTypes.DROWNED, "DrownedConversionTime");
        checkConversion(test, EntityTypes.HUSK, EntityTypes.ZOMBIE, "DrownedConversionTime");
        test.succeed();
    }

    @GameTest
    public void curingAndInfectionCreateFreshOwners(GameTestHelper test) {
        checkConversion(test, EntityTypes.ZOMBIE_VILLAGER, EntityTypes.VILLAGER, "ConversionTime");
        var villager = test.spawn(EntityTypes.VILLAGER, SPAWN, SPAWN, SPAWN);
        var before = body(villager);
        var killer = spawn(test, EntityTypes.ZOMBIE);
        test.assertTrue(killer.convertVillagerToZombieVillager(test.getLevel(), villager), "原版村民转化失败");
        var fresh = converted(test, villager, EntityTypes.ZOMBIE_VILLAGER);
        var after = body(fresh);
        test.assertTrue(!after.getId().equals(before.getId()), "僵尸村民带走生人身体");
        CorpseSpeciesGameTest.assertFresh(test, after);
        test.succeed();
    }

    private static void checkConversion(GameTestHelper test, EntityType<? extends Zombie> from,
                                        EntityType<? extends Mob> to, String timer) {
        // 从原版倒计时的最后一刻读入，实际转换、移除与区域释放均走实体 tick。
        var original = spawn(test, from);
        var before = damage(original, part("abdomen"), WOUND, DamageKind.CUSTOM);
        var saved = save(original);
        saved.putInt(timer, 0);
        load(original, saved);
        body(original);
        original.tick();
        var next = converted(test, original, to);
        var after = body(next);
        test.assertTrue(!after.getId().equals(before.getId()), "转化复用了旧身体身份");
        if (to == EntityTypes.VILLAGER) {
            test.assertTrue(after.getTemplate().getId().equals("human:adult"), "治愈没有生成生人身体");
        } else {
            CorpseSpeciesGameTest.assertFresh(test, after);
        }
        next.discard();
    }

    private static <T extends Mob> T converted(GameTestHelper test, Mob old, EntityType<T> to) {
        test.assertTrue(old.isRemoved() && ((HumanoidEntityAccess) old).meridian$humanoidReleaseQueued(),
                "旧个体未经过移除释放");
        return test.getLevel().getEntities(to, old.getBoundingBox().inflate(1), LivingEntity::isAlive)
                .getFirst();
    }

    private static double hp(LivingEntity mob) {
        return body(mob).getPartStatesList().stream().mapToDouble(PartState::getHp).sum();
    }
}
