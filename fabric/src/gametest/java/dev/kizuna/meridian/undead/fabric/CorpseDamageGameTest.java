package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;

import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.OrganState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueState;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;

public final class CorpseDamageGameTest {
    private static final int DESTROY = 100;
    private static final double RATIO = 0.2;
    private static final double BRONZE_HIDE_PROTECTED_SHARE = 0.6;
    private static final double BRONZE_HIDE_UNPROTECTED_SHARE = 0.4;
    private static final double DAMAGE_DOSE = 10;
    private static final double DAMAGE_TOLERANCE = 1e-9;

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

    @GameTest
    public void bronzeHideProtectsPartSkinAndBoneButPreservesMuscleAndPearl(GameTestHelper test) {
        var plain = spawn(test, EntityTypes.ZOMBIE);
        var protectedCorpse = spawn(test, EntityTypes.ZOMBIE);
        var plainBefore = body(plain);
        var protectedBefore = body(protectedCorpse);
        var plainAfter = damage(plain, part("chest"), DAMAGE_DOSE, DamageKind.BLUNT);
        var protectedAfter = protectedDamage(protectedCorpse, part("chest"), DAMAGE_DOSE,
                DamageKind.BLUNT, BRONZE_HIDE_PROTECTED_SHARE);

        assertScaledLoss(test, partLoss(plainBefore, plainAfter, "chest"),
                partLoss(protectedBefore, protectedAfter, "chest"), "胸部位");
        assertScaledLoss(test, tissueLoss(plainBefore, plainAfter, "chest_skin"),
                tissueLoss(protectedBefore, protectedAfter, "chest_skin"), "皮");
        assertScaledLoss(test, tissueLoss(plainBefore, plainAfter, "bone_thoracic"),
                tissueLoss(protectedBefore, protectedAfter, "bone_thoracic"), "骨");
        assertSameLoss(test, tissueLoss(plainBefore, plainAfter, "diaphragm"),
                tissueLoss(protectedBefore, protectedAfter, "diaphragm"), "肌肉");
        assertSameLoss(test, organLoss(plainBefore, plainAfter, "heart"),
                organLoss(protectedBefore, protectedAfter, "heart"), "内脏");

        var plainAbdomenBefore = body(plain);
        var protectedAbdomenBefore = body(protectedCorpse);
        var plainAbdomenAfter = damage(plain, part("abdomen"), DAMAGE_DOSE, DamageKind.BLUNT);
        var protectedAbdomenAfter = protectedDamage(protectedCorpse, part("abdomen"), DAMAGE_DOSE,
                DamageKind.BLUNT, BRONZE_HIDE_PROTECTED_SHARE);
        assertSameLoss(test, organLoss(plainAbdomenBefore, plainAbdomenAfter, "corpse_pearl"),
                organLoss(protectedAbdomenBefore, protectedAbdomenAfter, "corpse_pearl"), "尸丹");
        test.succeed();
    }

    private static void assertScaledLoss(GameTestHelper test, double plainLoss, double protectedLoss, String label) {
        double expected = plainLoss * BRONZE_HIDE_UNPROTECTED_SHARE;
        test.assertTrue(Math.abs(protectedLoss - expected) <= DAMAGE_TOLERANCE,
                label + " 保护后应为原伤量的四成");
    }

    private static void assertSameLoss(GameTestHelper test, double plainLoss, double protectedLoss, String label) {
        test.assertTrue(Math.abs(protectedLoss - plainLoss) <= DAMAGE_TOLERANCE,
                label + " 不应受铜皮影响");
    }

    private static double partLoss(BodySnapshot before, BodySnapshot after, String id) {
        return partHp(before, id) - partHp(after, id);
    }

    private static double tissueLoss(BodySnapshot before, BodySnapshot after, String id) {
        return tissueHp(before, id) - tissueHp(after, id);
    }

    private static double organLoss(BodySnapshot before, BodySnapshot after, String id) {
        return organHp(before, id) - organHp(after, id);
    }

    private static double partHp(BodySnapshot body, String id) {
        return body.getPartStatesList().stream().filter(state -> state.getId().equals(id))
                .mapToDouble(PartState::getHp).findFirst().orElseThrow();
    }

    private static double tissueHp(BodySnapshot body, String id) {
        return body.getTissueStatesList().stream().filter(state -> state.getId().equals(id))
                .mapToDouble(TissueState::getHp).findFirst().orElseThrow();
    }

    private static double organHp(BodySnapshot body, String id) {
        return body.getOrganStatesList().stream().filter(state -> state.getId().equals(id))
                .mapToDouble(OrganState::getHp).findFirst().orElseThrow();
    }
}
