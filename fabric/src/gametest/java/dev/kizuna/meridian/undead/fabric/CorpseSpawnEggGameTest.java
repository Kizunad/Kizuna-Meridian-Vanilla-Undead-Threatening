package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class CorpseSpawnEggGameTest {
    private static final int POSITION = 2;
    private static final int SPAWN_RANGE = 4;
    private static final int ATTEMPTS = 128;
    private static final int SPAWNER_ROUNDS = 2;
    private static final Identifier ICON_ID = Identifier.fromNamespaceAndPath(
            "meridian_undead", "zombie_hairy_spawn_egg");
    private static final List<Egg> EGGS = List.of(
            new Egg("drowned_black_spawn_egg", EntityTypes.DROWNED, HumanoidTier.WANDERING_QI),
            new Egg("drowned_hairy_spawn_egg", EntityTypes.DROWNED, HumanoidTier.TRUE_QI),
            new Egg("husk_black_spawn_egg", EntityTypes.HUSK, HumanoidTier.WANDERING_QI),
            new Egg("husk_hairy_spawn_egg", EntityTypes.HUSK, HumanoidTier.TRUE_QI),
            new Egg("zombie_black_spawn_egg", EntityTypes.ZOMBIE, HumanoidTier.WANDERING_QI),
            new Egg("zombie_hairy_spawn_egg", EntityTypes.ZOMBIE, HumanoidTier.TRUE_QI),
            new Egg("zombie_villager_black_spawn_egg", EntityTypes.ZOMBIE_VILLAGER, HumanoidTier.WANDERING_QI),
            new Egg("zombie_villager_hairy_spawn_egg", EntityTypes.ZOMBIE_VILLAGER, HumanoidTier.TRUE_QI));

    @GameTest
    public void corpseTabListsEightEggsInOrder(GameTestHelper test) {
        var level = test.getLevel();
        var display = new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(),
                true /* hasPermissions */, level.registryAccess());
        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(UndeadSpawnEggs.TAB_ID);
        tab.buildContents(display);
        var ids = tab.getDisplayItems().stream().map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem())).toList();
        test.assertTrue(ids.equals(EGGS.stream().map(Egg::id).toList()), "尸修创造页蛋数量或顺序错误 " + ids);
        var iconId = BuiltInRegistries.ITEM.getKey(tab.getIconItem().getItem());
        test.assertTrue(iconId.equals(ICON_ID), "尸修创造页未用僵尸毛僵蛋图标");
        test.succeed();
    }

    @GameTest
    public void eightEggsHatchTheirAdultRanksOnBlocks(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(POSITION, 1, POSITION));
        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        var player = test.makeMockPlayer(GameType.SURVIVAL);
        for (var egg : EGGS) {
            var stack = egg.stack();
            use(test, player, stack, pos);
            var spawned = level.getEntitiesOfClass(Zombie.class, new AABB(pos.above()), Zombie::isAlive);
            test.assertTrue(spawned.size() == 1, "使用蛋后没有恰好生成一只僵尸 " + egg.path());
            var mob = spawned.getFirst();
            assertHatched(test, mob, egg, EntitySpawnReason.SPAWN_ITEM_USE);
            mob.discard();
        }
        test.succeed();
    }

    @GameTest
    public void eightEggsRetargetSpawnersAndPersistRanks(GameTestHelper test) {
        var level = test.getLevel();
        var pos = test.absolutePos(new BlockPos(POSITION, POSITION, POSITION));
        var player = test.makeMockServerPlayerInLevel();
        var rules = level.getGameRules();
        boolean wasEnabled = rules.get(GameRules.SPAWNER_BLOCKS_WORK);
        try {
            rules.set(GameRules.SPAWNER_BLOCKS_WORK, true /* enabled */, level.getServer());
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(Vec3.atCenterOf(pos.east(SPAWN_RANGE)));
            level.setBlockAndUpdate(pos, Blocks.SPAWNER.defaultBlockState());
            var cage = (SpawnerBlockEntity) level.getBlockEntity(pos);
            initializeSpawner(test, cage);
            for (var egg : EGGS) {
                assertSpawnerRound(test, cage, player, egg);
            }
            use(test, player, new ItemStack(Items.ZOMBIE_VILLAGER_SPAWN_EGG), pos);
            var spawned = tickSpawner(test, cage, Zombie::isBaby);
            test.assertTrue(spawned.getType() == EntityTypes.ZOMBIE_VILLAGER, "原版蛋未改回目标物种");
            CorpseBirthGameTest.assertBirthRank(test, CorpseTestBodies.body(spawned), HumanoidTier.MORTAL);
            spawned.discard();
        } finally {
            rules.set(GameRules.SPAWNER_BLOCKS_WORK, wasEnabled, level.getServer());
            level.getServer().getPlayerList().remove(player);
        }
        test.succeed();
    }

    private static void initializeSpawner(GameTestHelper test, SpawnerBlockEntity cage) {
        var entity = new CompoundTag();
        entity.putString("id", "minecraft:pig");
        var data = new CompoundTag();
        data.put("entity", entity);
        data.put("custom_spawn_rules", new CompoundTag());
        var tag = new CompoundTag();
        tag.put("SpawnData", data);
        tag.putInt("SpawnCount", 1);
        reload(test, cage, tag);
    }

    private static void assertSpawnerRound(GameTestHelper test, SpawnerBlockEntity cage, Player player, Egg egg) {
        var level = test.getLevel();
        var pos = cage.getBlockPos();
        use(test, player, egg.stack(), pos);
        var nearby = level.getEntitiesOfClass(Zombie.class, new AABB(pos).inflate(SPAWN_RANGE), Zombie::isAlive);
        test.assertTrue(nearby.isEmpty(), "改笼时提前生成了僵尸 " + egg.path());
        var saved = cage.saveWithoutMetadata(level.registryAccess());
        String savedType = saved.getCompound("SpawnData").orElseThrow()
                .getCompound("entity").orElseThrow()
                .getString("id").orElseThrow();
        test.assertTrue(savedType.equals(BuiltInRegistries.ENTITY_TYPE.getKey(egg.type()).toString()),
                "刷怪笼出生数据未写入指定物种 " + egg.path());
        reload(test, cage, saved);
        for (int round = 0; round < SPAWNER_ROUNDS; round++) {
            var mob = tickSpawner(test, cage, zombie -> false);
            assertHatched(test, mob, egg, EntitySpawnReason.SPAWNER);
            mob.discard();
        }
    }

    private static Zombie tickSpawner(GameTestHelper test, SpawnerBlockEntity cage, Predicate<Zombie> ignore) {
        var level = test.getLevel();
        var pos = cage.getBlockPos();
        var area = new AABB(pos).inflate(SPAWN_RANGE);
        var tag = cage.saveWithoutMetadata(level.registryAccess());
        tag.putShort("Delay", (short) 0);
        reload(test, cage, tag);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            cage.getSpawner().serverTick(level, pos);
            var mobs = level.getEntitiesOfClass(Zombie.class, area, Zombie::isAlive);
            if (mobs.size() == 1) {
                var mob = mobs.getFirst();
                if (!ignore.test(mob)) {
                    return mob;
                }
                mob.discard();
                reload(test, cage, tag);
            }
        }
        throw new AssertionError("刷怪笼未产出一只僵尸");
    }

    private static void reload(GameTestHelper test, SpawnerBlockEntity cage, CompoundTag tag) {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, test.getLevel().registryAccess(), tag);
        cage.loadWithComponents(input);
    }

    private static void use(GameTestHelper test, Player player, ItemStack stack, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false /* insideBlock */);
        var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        test.assertTrue(result instanceof InteractionResult.Success && stack.isEmpty(), "刷怪蛋未成功使用 " + pos);
    }

    private static void assertHatched(GameTestHelper test, Zombie mob, Egg egg, EntitySpawnReason reason) {
        test.assertTrue(mob.getType() == egg.type() && !mob.isBaby(), "刷怪蛋实体类型或成年状态错误 " + egg.path());
        CorpseBirthGameTest.assertBirthRank(test, CorpseTestBodies.body(mob), egg.tier());
        var plan = ((HumanoidEntityAccess) mob).meridian$birthPlan();
        test.assertTrue(plan != null && plan.spawn().reason().equals(reason.name())
                && plan.spawn().requested().orElse(null) == egg.tier(), "未记录出生来路与指定道行 " + egg.path());
        var restored = egg.type().create(test.getLevel(), EntitySpawnReason.LOAD);
        CorpseTestBodies.load(restored, CorpseTestBodies.save(mob));
        test.assertTrue(plan.equals(((HumanoidEntityAccess) restored).meridian$birthPlan()), "读档丢失出生事实");
    }

    private record Egg(String path, EntityType<? extends Zombie> type, HumanoidTier tier) {
        Identifier id() {
            return Identifier.fromNamespaceAndPath("meridian_undead", path);
        }

        ItemStack stack() {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(id()));
        }
    }
}
