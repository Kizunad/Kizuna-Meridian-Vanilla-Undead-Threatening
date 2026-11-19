package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

public final class CorpseCombatGameTest {
    private static final int DOSE = 4;
    private static final long AIM_SEED = 31;
    private static final int LIMIT = 200;

    @GameTest
    public void aimedWeaponHitsAgainstCorpseAndVillager(GameTestHelper test) {
        // 两种身体使用正式安装的规则，每次从同一瞄准序列开始。
        var attacker = test.spawn(EntityTypes.COW, SPAWN, SPAWN, SPAWN);
        for (String kind : List.of("blunt", "cutting")) {
            var weapon = kind.equals("cutting") ? Items.IRON_SWORD : Items.STICK;
            attacker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(weapon));
            int head = compare(test, new Trial(attacker, kind, "head"));
            int torso = compare(test, new Trial(attacker, kind, "torso"));
            test.assertTrue(head < torso, "集中击头应比躯干组快 " + kind);
            compare(test, new Trial(attacker, kind, "limbs"));
        }
        test.succeed();
    }

    private record Trial(Mob attacker, String kind, String group) { }

    private static int compare(GameTestHelper test, Trial trial) {
        // 只有死亡的目标才有致死击数，达到观察上限的存活者记为零。
        int villager = strikes(test, EntityTypes.VILLAGER, trial);
        int corpse = strikes(test, EntityTypes.ZOMBIE, trial);
        test.assertTrue(villager > 0 && corpse == villager,
                trial.kind() + " " + trial.group() + " 致死击数不同，白僵 " + corpse + "，村民 " + villager);
        return villager;
    }

    private static int strikes(GameTestHelper test, EntityType<? extends Mob> type, Trial trial) {
        // 每击清掉原版无敌帧，保留真实伤害来源、选点和身体结算。
        var target = test.spawn(type, SPAWN, SPAWN, SPAWN);
        var initial = body(target);
        var prefs = VanillaDamageFabric.preferences();
        test.assertTrue(prefs.weights(initial, "minecraft:cow", "head").keySet().equals(Set.of("head")),
                "尸身缺少头部瞄准分组");
        target.getRandom().setSeed(AIM_SEED);
        int hits = 0;
        String group = trial.group();
        var level = test.getLevel();
        while (target.isAlive() && hits < LIMIT) {
            String aimed;
            if (group.equals("limbs")) {
                aimed = hits % 2 == 0 ? "arms" : "legs";
            } else {
                aimed = group;
            }
            var source = source(test, trial.attacker(), trial.kind() + "_" + aimed);
            target.invulnerableTime = 0;
            target.hurtServer(level, source, DOSE);
            hits++;
        }
        boolean dead = !target.isAlive();
        LoggerFactory.getLogger("meridian_undead_test").info("击数 {} {} {}：{} 下，原版伤害 {}，死亡 {}",
                type, trial.kind(), group, hits, hits * DOSE, dead);
        target.discard();
        return dead ? hits : 0;
    }

    private static DamageSource source(GameTestHelper test, LivingEntity attacker, String path) {
        var registry = test.getLevel().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        var id = Identifier.fromNamespaceAndPath("meridian_undead_test", path);
        var key = ResourceKey.create(Registries.DAMAGE_TYPE, id);
        return new DamageSource(registry.getOrThrow(key), attacker);
    }
}
