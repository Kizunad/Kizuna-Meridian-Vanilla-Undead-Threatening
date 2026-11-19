package dev.kizuna.meridian.undead.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostRequest;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.EnvironmentHostResponse;
import dev.kizuna.meridian.human.protocol.pb.EnvironmentHostMessages.RegionQuery;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.server.EnvironmentHostEntrypoint;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

public final class CorpseBirthReleaseGameTest {
    private static final int REGION_DISTANCE = 8192;
    private static final int WINDOW_TICKS = 600;
    private static final int HANDOFF_TICKS = 40;
    private static final double RECEIPT_TOLERANCE = 0.0000001;

    @GameTest(maxTicks = WINDOW_TICKS + HANDOFF_TICKS)
    public void killedAndDiscardedPearlsCreditTheirOwnRegions(GameTestHelper test) {
        var world = test.getLevel();
        var origin = test.absolutePos(BlockPos.ZERO);
        var death = origin.offset(REGION_DISTANCE, 0, 0);
        var discard = origin.offset(0, 0, REGION_DISTANCE);
        double deathBefore = balance(test, death);
        double discardBefore = balance(test, discard);
        var black = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.WANDERING_QI);
        var hairy = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.TRUE_QI);
        black.setPos(Vec3.atCenterOf(death));
        hairy.setPos(Vec3.atCenterOf(discard));
        black.kill(world);
        // 原版死亡动画在移除刻结束，测试位置没有玩家维持实体刻。
        black.remove(RemovalReason.KILLED);
        hairy.discard();
        test.succeedWhen(() -> {
            double deathCredit = balance(test, death) - deathBefore;
            double discardCredit = balance(test, discard) - discardBefore;
            test.assertTrue(Math.abs(deathCredit - CorpseBirthGameTest.BLACK_QI) < RECEIPT_TOLERANCE,
                    "死亡区域实际到账 " + deathCredit);
            test.assertTrue(Math.abs(discardCredit - CorpseBirthGameTest.HAIRY_QI) < RECEIPT_TOLERANCE,
                    "丢弃区域实际到账 " + discardCredit);
        });
    }

    private static double balance(GameTestHelper test, BlockPos pos) {
        var world = test.getLevel();
        var position = RegionQuery.newBuilder()
                .setDimensionId(world.dimension().identifier().toString())
                .setBlockX(pos.getX())
                .setBlockZ(pos.getZ())
                .build();
        var request = EnvironmentHostRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setProtocolVersion(1)
                .setQuery(position)
                .build();
        var endpoint = FabricLoader.getInstance().getEntrypoints("meridian_environment_host",
                EnvironmentHostEntrypoint.class).getFirst();
        try {
            var response = EnvironmentHostResponse.parseFrom(endpoint.exchange(request.toByteArray()));
            test.assertTrue(response.hasRegion(), "验收位置没有区域");
            return BodyProtobuf.fromInventory(response.getRegion().getInventory()).totalQi();
        } catch (InvalidProtocolBufferException failure) {
            throw new AssertionError("区域余额响应损坏", failure);
        }
    }
}
