package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class CorpseSpawnSourcesGameTest {
    private static final int ATTEMPTS = 128;
    private static final int ROOM_HEIGHT = 48;
    private static final int PLAYER_DISTANCE = 48;
    private static final int CAVE_RADIUS = 18;
    private static final int CAVE_HEIGHT = 3;
    private static final int LIGHT_FLAGS = 3;
    private static final int STRUCTURE_WIDTH = 3;
    private static final int LIGHT_SETTLE_TICKS = 12;

    @GameTest
    public void vanillaEggSummonAndStructureAreWhite(GameTestHelper test) throws Exception {
        var config = Files.createTempFile("corpse-placed-", ".toml");
        try {
            CorpseBirthGameTest.force(config, HumanoidTier.TRUE_QI);
            var origin = test.absolutePos(new BlockPos(SPAWN, 1, SPAWN));
            var level = test.getLevel();
            level.setBlockAndUpdate(origin, Blocks.STONE.defaultBlockState());
            var player = test.makeMockPlayer(GameType.SURVIVAL);
            var egg = new ItemStack(Items.HUSK_SPAWN_EGG);
            player.setItemInHand(InteractionHand.MAIN_HAND, egg);
            var hit = new BlockHitResult(Vec3.atCenterOf(origin), Direction.UP, origin, false /* inside */);
            egg.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            var hatched = only(test, origin.above(), EntityTypes.HUSK);
            assertWhiteOrBaby(test, hatched);
            hatched.discard();
            var source = level.getServer().createCommandSourceStack().withLevel(level)
                    .withPosition(Vec3.atCenterOf(origin.above()));
            level.getServer().getCommands().performPrefixedCommand(source, "summon minecraft:husk");
            var summoned = only(test, origin.above(), EntityTypes.HUSK);
            assertWhiteOrBaby(test, summoned);
            summoned.discard();
            assertStructure(test, origin.above());
        } finally {
            UndeadFabric.reloadConfig(CorpseBirthGameTest.CONFIG);
            Files.deleteIfExists(config);
        }
        test.succeed();
    }

    private static void assertStructure(GameTestHelper test, BlockPos origin) {
        var level = test.getLevel();
        var template = new StructureTemplate();
        // 模拟原版结构保存的实体，只写结构自身的 NBT。复制已建身的活体会把身体一并写进结构。
        var mob = EntityTypes.HUSK.create(level, EntitySpawnReason.STRUCTURE);
        mob.setPos(Vec3.atCenterOf(origin));
        level.addFreshEntity(mob);
        template.fillFromWorld(level, origin, new Vec3i(1, STRUCTURE_WIDTH, 1), true /* includeEntities */, List.of());
        mob.discard();
        var rules = new StructurePlaceSettings().setFinalizeEntities(true /* finalizeEntities */);
        template.placeInWorld(level, origin, origin, rules, level.getRandom(), LIGHT_FLAGS);
        assertWhiteOrBaby(test, only(test, origin, EntityTypes.HUSK));
    }

    private static Zombie only(GameTestHelper test, BlockPos pos, EntityType<? extends Zombie> type) {
        var group = test.getLevel().getEntities(type, new AABB(pos).inflate(1), Entity::isAlive);
        test.assertTrue(group.size() == 1, "放置位置应有一只 " + type + "，实有 " + group.size());
        return group.getFirst();
    }

    private static void assertWhiteOrBaby(GameTestHelper test, Zombie mob) {
        if (mob.isBaby()) {
            test.assertTrue(!save(mob).contains(SAVE_KEY), "幼体有身体");
        } else {
            test.assertTrue(HumanoidTier.of(body(mob)) == HumanoidTier.MORTAL, "非自然来源获得道行");
        }
    }

    @GameTest
    public void vanillaSpawnerCreatesWhiteCorpses(GameTestHelper test) throws Exception {
        var config = Files.createTempFile("corpse-spawner-", ".toml");
        var level = test.getLevel();
        var player = test.makeMockServerPlayerInLevel();
        var pos = test.absolutePos(new BlockPos(SPAWN, SPAWN, SPAWN));
        var rules = level.getGameRules();
        boolean wasEnabled = rules.get(GameRules.SPAWNER_BLOCKS_WORK);
        try {
            CorpseBirthGameTest.force(config, HumanoidTier.TRUE_QI);
            rules.set(GameRules.SPAWNER_BLOCKS_WORK, true /* enabled */, level.getServer());
            player.setPos(Vec3.atCenterOf(pos.east(STRUCTURE_WIDTH)));
            level.setBlockAndUpdate(pos, Blocks.SPAWNER.defaultBlockState());
            var cage = (SpawnerBlockEntity) level.getBlockEntity(pos);
            var spawnData = new CompoundTag();
            var entity = new CompoundTag();
            entity.putString("id", "minecraft:husk");
            spawnData.put("entity", entity);
            spawnData.put("custom_spawn_rules", new CompoundTag());
            var settings = new CompoundTag();
            settings.put("SpawnData", spawnData);
            settings.putShort("Delay", (short) 0);
            settings.putInt("SpawnCount", 1);
            var spawner = cage.getSpawner();
            var input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), settings);
            spawner.load(level, pos, input);
            var area = new AABB(pos).inflate(CAVE_RADIUS);
            Set<UUID> prior = level.getEntities(EntityTypes.HUSK, area, Entity::isAlive).stream()
                    .map(Entity::getUUID).collect(Collectors.toSet());
            for (int i = 0; i < ATTEMPTS; i++) {
                spawner.serverTick(level, pos);
                var adults = level.getEntities(EntityTypes.HUSK, area,
                        mob -> !mob.isBaby() && !prior.contains(mob.getUUID()));
                if (!adults.isEmpty()) {
                    assertWhiteOrBaby(test, adults.getFirst());
                    test.succeed();
                    return;
                }
            }
            test.fail("原版刷怪笼未产生成年尸壳");
        } finally {
            rules.set(GameRules.SPAWNER_BLOCKS_WORK, wasEnabled, level.getServer());
            UndeadFabric.reloadConfig(CorpseBirthGameTest.CONFIG);
            Files.deleteIfExists(config);
            level.getServer().getPlayerList().remove(player);
        }
    }

    @GameTest
    public void vanillaNaturalSpawnerSelectsCultivators(GameTestHelper test) throws Exception {
        var level = test.getLevel();
        var room = test.absolutePos(new BlockPos(SPAWN, ROOM_HEIGHT, SPAWN));
        var source = level.getServer().createCommandSourceStack().withLevel(level)
                .withPosition(Vec3.atCenterOf(room));
        level.getServer().getCommands().performPrefixedCommand(source,
                "fillbiome ~-12 ~-4 ~-12 ~12 ~4 ~12 minecraft:plains");
        for (var pos : BlockPos.betweenClosed(room.offset(-CAVE_RADIUS, -1, -CAVE_RADIUS),
                room.offset(CAVE_RADIUS, CAVE_HEIGHT, CAVE_RADIUS))) {
            boolean shell = pos.getY() == room.getY() - 1 || pos.getY() == room.getY() + CAVE_HEIGHT;
            var material = shell ? Blocks.STONE : Blocks.AIR;
            level.setBlock(pos, material.defaultBlockState(), LIGHT_FLAGS);
        }
        test.runAfterDelay(LIGHT_SETTLE_TICKS, () -> {
            try {
                spawnInDarkRoom(test, room);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });
    }

    private static void spawnInDarkRoom(GameTestHelper test, BlockPos room) throws Exception {
        var level = test.getLevel();
        var config = Files.createTempFile("corpse-natural-", ".toml");
        var player = test.makeMockServerPlayerInLevel();
        try {
            CorpseBirthGameTest.force(config, HumanoidTier.TRUE_QI);
            player.setPos(Vec3.atCenterOf(room.below(PLAYER_DISTANCE)));
            var chunk = level.getChunkAt(room);
            var spawned = new ArrayList<Zombie>();
            for (int i = 0; i < ATTEMPTS && spawned.isEmpty(); i++) {
                NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, chunk, room,
                        (type, pos, area) -> type == EntityTypes.ZOMBIE,
                        (mob, area) -> {
                            if (!mob.isBaby()) {
                                spawned.add((Zombie) mob);
                            }
                        });
            }
            test.assertTrue(!spawned.isEmpty(), "原版自然刷新未产生成年僵尸，群系 " + level.getBiome(room)
                    + "，亮度 " + level.getMaxLocalRawBrightness(room) + "，难度 " + level.getDifficulty()
                    + "，玩家距离 " + player.distanceToSqr(Vec3.atCenterOf(room)));
            for (var mob : spawned) {
                test.assertTrue(HumanoidTier.of(body(mob)) == HumanoidTier.TRUE_QI, "真实自然刷新未出毛僵");
                mob.discard();
            }
            assertReinforcement(test, room);
        } finally {
            level.getServer().getPlayerList().remove(player);
            UndeadFabric.reloadConfig(CorpseBirthGameTest.CONFIG);
            Files.deleteIfExists(config);
        }
        test.succeed();
    }

    private static void assertReinforcement(GameTestHelper test, BlockPos room) {
        var world = test.getLevel();
        var server = world.getServer();
        var oldDifficulty = world.getDifficulty();
        var rules = world.getGameRules();
        boolean oldSpawning = rules.get(GameRules.SPAWN_MONSTERS);
        var target = EntityTypes.COW.create(world, EntitySpawnReason.COMMAND);
        var caller = EntityTypes.ZOMBIE.create(world, EntitySpawnReason.COMMAND);
        try {
            server.setDifficulty(Difficulty.HARD, true /* forceDifficulty */);
            rules.set(GameRules.SPAWN_MONSTERS, true /* enabled */, server);
            target.setPos(Vec3.atCenterOf(room.above()));
            caller.setPos(Vec3.atCenterOf(room));
            world.addFreshEntity(target);
            world.addFreshEntity(caller);
            caller.setTarget(target);
            var area = new AABB(room).inflate(Zombie.REINFORCEMENT_RANGE_MAX);
            for (int i = 0; i < ATTEMPTS && caller.isAlive(); i++) {
                caller.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE).setBaseValue(1);
                caller.invulnerableTime = 0;
                caller.hurtServer(world, caller.damageSources().mobAttack(target), 1);
                var arrivals = world.getEntities(EntityTypes.ZOMBIE, area,
                        mob -> mob != caller && !mob.isBaby() && mob.getTarget() == target);
                if (!arrivals.isEmpty()) {
                    assertWhiteOrBaby(test, arrivals.getFirst());
                    return;
                }
            }
            test.fail("原版受伤入口没有叫到成年援兵");
        } finally {
            server.setDifficulty(oldDifficulty, true /* forceDifficulty */);
            rules.set(GameRules.SPAWN_MONSTERS, oldSpawning, server);
            caller.discard();
            target.discard();
        }
    }
}
