package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeciesBirths;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** 在真实实体刻中测有目标和无目标的成年黑僵。 */
public final class CorpseCrowdPerfGameTest {
    private static final int CROWD = 100;
    private static final int SIDE = 5;
    private static final int FLOOR_HEIGHT = 3;
    private static final int TARGET_STRIDE = 5;
    private static final int NIGHT = 18000;
    private static final int WARMUP = 200;
    private static final int WINDOW = 600;
    private static final int TIMEOUT = WARMUP + WINDOW + 40;
    private static final int P95_INDEX = (95 * WINDOW - 1) / 100;
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double MAX_MEAN_MS = 50;
    private static final Identifier BEFORE_AI = Identifier.parse("meridian_undead_test:before_ai");
    private static boolean listening;
    private static long started;
    private static long elapsed;

    @GameTest(maxTicks = TIMEOUT)
    public void hundredZombiesMeasureTickCost(GameTestHelper test) {
        if (!"perf".equals(System.getProperty("meridian.repro.mode"))) {
            test.succeed();
            return;
        }
        listen();
        test.setTime(NIGHT);
        List<Zombie> crowd = spawn(test);
        for (int index = 0; index < CROWD; index += TARGET_STRIDE) {
            crowd.get(index).setTarget(crowd.get(index + 1));
        }
        test.runAfterDelay(WARMUP, () -> measure(test, crowd, new long[WINDOW], 0));
    }

    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        // 起点要在默认阶段之前，才能把服务端 tick 开头的 AI 也算进去。
        ServerTickEvents.START_SERVER_TICK.addPhaseOrdering(BEFORE_AI, Event.DEFAULT_PHASE);
        ServerTickEvents.START_SERVER_TICK.register(BEFORE_AI, server -> started = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(server -> elapsed = System.nanoTime() - started);
    }

    private static List<Zombie> spawn(GameTestHelper test) {
        var crowd = new ArrayList<Zombie>(CROWD);
        var level = test.getLevel();
        BlockPos origin = test.absolutePos(BlockPos.ZERO);
        // 测试结构只保证所在区块跑实体刻，四层都放在这个区块中部。
        var chunk = ChunkPos.containing(origin);
        BlockPos first = chunk.getMiddleBlockPosition(origin.getY() + FLOOR_HEIGHT);
        for (int index = 0; index < CROWD; index++) {
            int floor = index / (SIDE * SIDE);
            BlockPos pos = first.offset(index % SIDE, floor * FLOOR_HEIGHT, index / SIDE % SIDE);
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
            var mob = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
            UUID id = UUID.nameUUIDFromBytes(("corpse-perf/" + index).getBytes(StandardCharsets.UTF_8));
            mob.setUUID(id);
            mob.getRandom().setSeed(id.getMostSignificantBits());
            mob.setPos(Vec3.atBottomCenterOf(pos));
            mob.setPersistenceRequired();
            // 去掉原版漫游和近战目标，尸修 AI 照常运行，近身目标在整个窗口保持存活。
            mob.removeFreeWill();
            HumanoidSpeciesBirths.birth(mob, HumanoidTier.WANDERING_QI);
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            test.assertTrue(level.addFreshEntity(mob), "黑僵入世失败 " + index);
            crowd.add(mob);
        }
        return crowd;
    }

    private static void measure(GameTestHelper test, List<Zombie> crowd, long[] samples, int index) {
        samples[index] = elapsed;
        for (int i = 0; i < CROWD; i++) {
            Zombie mob = crowd.get(i);
            Zombie expected = null;
            if (i % TARGET_STRIDE == 0) {
                expected = crowd.get(i + 1);
            }
            if (!mob.isAlive() || !mob.onGround() || mob.getTarget() != expected
                    || mob.tickCount < WARMUP + index - 1) {
                test.fail("采样队列失效 index=" + i + " tick=" + mob.tickCount + " sample=" + index
                        + " ground=" + mob.onGround() + " alive=" + mob.isAlive() + " target=" + mob.getTarget());
            }
        }
        if (index + 1 < WINDOW) {
            test.runAfterDelay(1, () -> measure(test, crowd, samples, index + 1));
            return;
        }
        Arrays.sort(samples);
        double mean = Arrays.stream(samples).average().orElseThrow() / NANOS_PER_MILLI;
        String summary = String.format(Locale.ROOT,
                "window=%d mean_ms=%.2f median_ms=%.2f p95_ms=%.2f max_ms=%.2f",
                WINDOW, mean, samples[WINDOW / 2] / NANOS_PER_MILLI, samples[P95_INDEX] / NANOS_PER_MILLI,
                samples[WINDOW - 1] / NANOS_PER_MILLI);
        LoggerFactory.getLogger("meridian_undead_perf").info("corpse-perf {}", summary);
        crowd.forEach(Zombie::discard);
        test.assertTrue(mean <= MAX_MEAN_MS, "百僵窗口均值超过 " + MAX_MEAN_MS + " ms " + summary);
        test.succeed();
    }
}
