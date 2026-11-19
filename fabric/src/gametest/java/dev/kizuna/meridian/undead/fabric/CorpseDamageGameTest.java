package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;

import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;

public final class CorpseDamageGameTest {
    private static final int DESTROY = 100;
    private static final double RATIO = 0.2;

    @GameTest
    public void headNeckChestAreLethal(GameTestHelper test) {
        for (String id : List.of("head", "neck", "chest")) {
            var zombie = spawn(test, EntityTypes.ZOMBIE);
            var result = damage(zombie, part(id), DESTROY, DamageKind.CUSTOM);
            test.assertTrue(result.getVitality().getDead(), id + " 归零应致死");
        }
        test.succeed();
    }

    @GameTest
    public void organDestructionAndLowerTorsoAreSurvivable(GameTestHelper test) {
        var zombie = spawn(test, EntityTypes.ZOMBIE);
        for (String id : List.of("heart", "left_lung", "right_lung", "brain", "corpse_pearl")) {
            var result = damage(zombie, organ(id), DESTROY, DamageKind.CUSTOM);
            test.assertTrue(result.getOrganStatesList().stream().anyMatch(s -> s.getId().equals(id) && s.getHp() == 0),
                    id + " 没有实际归零");
            test.assertTrue(!result.getVitality().getDead(), id + " 单独损坏杀死了尸身");
        }
        for (String id : List.of("abdomen", "pelvis")) {
            var result = damage(zombie, part(id), DESTROY, DamageKind.CUSTOM);
            test.assertTrue(!result.getVitality().getDead(), id + " 损坏时应存活");
        }
        test.succeed();
    }

    @GameTest
    public void onlyStrictlyBelowOneFifthDies(GameTestHelper test) {
        var zombie = spawn(test, EntityTypes.HUSK);
        var initial = body(zombie);
        for (var state : initial.getPartStatesList()) {
            damage(zombie, part(state.getId()), state.getHp() - state.getHp() * RATIO, DamageKind.CUSTOM);
        }
        test.assertTrue(!body(zombie).getVitality().getDead(), "恰好两成仍应存活");
        test.assertTrue(zombie.getHealth() > 0, "重伤存活时血条归零");
        var result = damage(zombie, part("abdomen"), 1, DamageKind.CUSTOM);
        test.assertTrue(result.getVitality().getDead(), "低于两成仍存活");
        test.succeed();
    }
}
