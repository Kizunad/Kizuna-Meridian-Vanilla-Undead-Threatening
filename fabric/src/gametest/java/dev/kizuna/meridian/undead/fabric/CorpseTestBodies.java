package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidHealthHost;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.HealthTarget;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageBody;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

final class CorpseTestBodies {
    static final int SPAWN = 2;
    static final String SAVE_KEY = "meridian_humanoid";
    private CorpseTestBodies() { }

    static Zombie spawn(GameTestHelper test, EntityType<? extends Zombie> type) {
        var entity = type.create(test.getLevel(), EntitySpawnReason.COMMAND);
        return add(test, entity);
    }

    static Zombie add(GameTestHelper test, Zombie entity) {
        entity.setPos(Vec3.atCenterOf(test.absolutePos(new BlockPos(SPAWN, SPAWN, SPAWN))));
        test.getLevel().addFreshEntity(entity);
        return entity;
    }

    static BodySnapshot body(LivingEntity entity) {
        return VanillaDamageFabric.inspectBody(entity).orElseThrow();
    }

    static CompoundTag save(LivingEntity entity) {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(out);
        return out.buildResult();
    }

    static void load(LivingEntity entity, CompoundTag saved) {
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, entity.registryAccess(), saved));
    }

    static BodySnapshot damage(LivingEntity entity, HealthTarget target, double dose, DamageKind kind) {
        // 测试直接提交到健康宿主，让本击经过物种裁决和框架的权威提交。
        var prior = body(entity);
        var damage = DamageBody.newBuilder()
                .setSourceId("meridian_undead:test")
                .setDamageType("meridian_undead:test")
                .setKind(kind)
                .setHit(target)
                .setDamageHp(dose)
                .build();
        var command = BodyCommand.newBuilder()
                .setBodyId(prior.getId())
                .setExpectedRevision(prior.getRevision())
                .setDamageBody(damage)
                .build();
        String id = UUID.randomUUID().toString();
        var envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setBodyCommand(command)
                .build();
        var request = HealthHostRequest.newBuilder()
                .setProtocolVersion(1)
                .setRequestId(id)
                .setSubjectId(entity.getStringUUID())
                .setEffect(envelope)
                .build();
        var reply = HealthHostProtobuf.decodeResponse(new HumanoidHealthHost().exchange(
                HealthHostProtobuf.encodeRequest(request)));
        if (!reply.hasBody()) {
            throw new AssertionError("宿主拒绝伤量 " + target + reply.getFailure());
        }
        return reply.getBody();
    }

    static HealthTarget part(String id) {
        return HealthTarget.newBuilder().setPartId(id).build();
    }

    static HealthTarget organ(String id) {
        return HealthTarget.newBuilder().setOrganId(id).build();
    }
}
