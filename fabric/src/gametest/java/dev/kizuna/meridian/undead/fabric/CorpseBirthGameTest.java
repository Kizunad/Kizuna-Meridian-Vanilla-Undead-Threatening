package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;

import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianDefinition;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.undead.server.UndeadContent;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.Zombie.ZombieGroupData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public final class CorpseBirthGameTest {
    static final int BLACK_QI = 10;
    static final int HAIRY_QI = 20;
    private static final int WHITE_CAPACITY = 10;
    private static final int BLACK_CAPACITY = 20;
    private static final int HAIRY_CAPACITY = 40;
    private static final Set<String> BLACK_CHANNELS = Set.of("ren", "left_lung_meridian", "right_lung_meridian",
            "left_stomach_meridian", "right_stomach_meridian");
    private static final Set<String> HAIRY_CHANNELS = Set.of("ren", "du", "left_lung_meridian", "right_lung_meridian",
            "left_stomach_meridian", "right_stomach_meridian", "left_large_intestine_meridian",
            "right_large_intestine_meridian", "left_spleen_meridian", "right_spleen_meridian");
    private static final double[][] DEFAULT_BOUNDARIES = {
            {0.75, 0.04, 0.045}, {3.75, 0.08, 0.0925}, {6.75, 0.12, 0.14}
    };
    private static final double MARGIN = 0.0000001;
    static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("meridian-undead.toml");

    @GameTest
    public void fourSpeciesUseTheirCommandedBirthRank(GameTestHelper test) {
        for (var type : CorpseSpeciesGameTest.TYPES) {
            for (var tier : HumanoidTier.values()) {
                var zombie = commanded(test, type, tier);
                var snapshot = body(zombie);
                test.assertTrue(HumanoidTier.of(snapshot) == tier, "命令未造出指定道行");
                switch (tier) {
                    case MORTAL -> assertRank(test, snapshot, Set.of(), WHITE_CAPACITY, 0);
                    case WANDERING_QI -> assertRank(test, snapshot, BLACK_CHANNELS, BLACK_CAPACITY, BLACK_QI);
                    case TRUE_QI -> assertRank(test, snapshot, HAIRY_CHANNELS, HAIRY_CAPACITY, HAIRY_QI);
                }
                zombie.discard();
            }
        }
        test.succeed();
    }

    private static void assertRank(GameTestHelper test, BodySnapshot body, Set<String> opened, int capacity, int qi) {
        var template = UndeadContent.bundle().getTemplates(0);
        var base = template.getStructure().getMeridiansList().stream()
                .collect(Collectors.toMap(MeridianDefinition::getId, MeridianDefinition::getLumenVolume));
        Realm realm = opened.equals(HAIRY_CHANNELS) ? Realm.TRUE_QI : Realm.WANDERING_QI;
        test.assertTrue(body.getRealm() == realm && body.getTemplate().equals(template.getReference()), "境界或模板错误");
        var report = UndeadInspect.describe(body);
        for (var definition : body.getStructure().getMeridiansList()) {
            var id = definition.getId();
            boolean open = opened.contains(id);
            double gain = open ? HumanOpening.LUMEN_VOLUME_GAIN : 0;
            test.assertTrue(definition.getLumenVolume() == base.get(id) + gain, "开拓内腔错误 " + id);
            var state = body.getMeridianStatesList().stream().filter(s -> s.getId().equals(id))
                    .findFirst().orElseThrow();
            var stage = open ? MeridianOpeningStage.OPENED : MeridianOpeningStage.UNOPENED;
            var opening = state.getOpening();
            test.assertTrue(opening.getStage() == stage && opening.getProgress() == (open ? 1 : 0),
                    "开脉状态或进度错误 " + id);
            test.assertTrue(state.getLumenQi().getEntriesCount() == 0 && state.getWallQi().getEntriesCount() == 0,
                    "经脉出生带元 " + id);
            if (open) {
                test.assertTrue(report.contains(definition.getDisplayName()), "inspect 漏经脉 " + id);
            }
        }
        assertPearl(test, body, capacity, qi);
    }

    private static void assertPearl(GameTestHelper test, BodySnapshot body, int capacity, int qi) {
        var pearl = body.getStructure().getOrgansList().stream().filter(o -> o.getId().equals(UndeadContent.PEARL))
                .findFirst().orElseThrow();
        test.assertTrue(pearl.getVolumeCapacity() * QiDensity.FREE == capacity, "个体尸丹容量错误");
        for (var organ : body.getOrganStatesList()) {
            var buffer = organ.getBuffer();
            if (qi == 0 || !organ.getId().equals(UndeadContent.PEARL)) {
                test.assertTrue(buffer.getEntriesCount() == 0, "多出的出生库存 " + organ.getId());
            } else {
                test.assertTrue(buffer.getEntriesCount() == 1, "尸丹明细数错误");
                var entry = buffer.getEntries(0);
                test.assertTrue(entry.getAmount() == qi && entry.getDensity() == QiDensity.FREE
                        && entry.getAttribute().equals("meridian:none"), "尸丹出生库存错误");
            }
        }
    }

    static Zombie commanded(GameTestHelper test, EntityType<? extends Zombie> type, HumanoidTier tier) {
        var pos = Vec3.atCenterOf(test.absolutePos(new BlockPos(SPAWN, SPAWN, SPAWN)));
        var world = test.getLevel();
        var output = new Feedback();
        var source = world.getServer().createCommandSourceStack().withLevel(world).withPosition(pos).withSource(output);
        var command = "meridian humanoid spawn " + UndeadFabric.SPECIES.get(type) + " " + tier.id();
        world.getServer().getCommands().performPrefixedCommand(source, command);
        String label = switch (tier) {
            case MORTAL -> "凡人";
            case WANDERING_QI -> "游炁";
            case TRUE_QI -> "真炁";
        };
        test.assertTrue(output.message.contains("（" + label + "）"), "生成档位回显错误 " + output.message);
        var id = UUID.fromString(output.message.substring(output.message.lastIndexOf('：') + 1));
        return (Zombie) world.getEntity(id);
    }

    private static final class Feedback implements CommandSource {
        String message = "";
        public void sendSystemMessage(Component text) { message = text.getString(); }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }

    @GameTest
    public void naturalChancesInterpolateExclusiveIntervals(GameTestHelper test) throws Exception {
        Path file = Files.createTempFile("corpse-defaults-", ".toml");
        try {
            var chances = CorpseConfig.load(file).chances();
            for (var row : DEFAULT_BOUNDARIES) {
                test.assertTrue(chances.natural(row[0], row[1] - MARGIN) == HumanoidTier.WANDERING_QI
                        && chances.natural(row[0], row[1] + MARGIN) == HumanoidTier.TRUE_QI
                        && chances.natural(row[0], row[2] - MARGIN) == HumanoidTier.TRUE_QI
                        && chances.natural(row[0], row[2] + MARGIN) == HumanoidTier.MORTAL, "概率端点错误 " + row[0]);
            }
        } finally {
            Files.deleteIfExists(file);
        }
        test.succeed();
    }

    static void force(Path file, HumanoidTier tier) throws Exception {
        int black = tier == HumanoidTier.WANDERING_QI ? 1 : 0;
        int hairy = tier == HumanoidTier.TRUE_QI ? 1 : 0;
        Files.writeString(file, "black_chance_min = %1$d\nblack_chance_max = %1$d\n".formatted(black)
                + "hairy_chance_min = %1$d\nhairy_chance_max = %1$d\n".formatted(hairy));
        UndeadFabric.reloadConfig(file);
    }

    @GameTest
    public void nonNaturalReasonsAndUnknownAreWhite(GameTestHelper test) throws Exception {
        Path alternate = Files.createTempFile("corpse-origins-", ".toml");
        try {
            for (var tier : HumanoidTier.values()) {
                force(alternate, tier);
                var natural = add(test, initialized(test, EntitySpawnReason.NATURAL));
                test.assertTrue(HumanoidTier.of(body(natural)) == tier, "自然生成未采用配置 " + tier);
            }
            for (var reason : EntitySpawnReason.values()) {
                if (reason != EntitySpawnReason.NATURAL) {
                    var mob = initialized(test, reason);
                    mob.addTag(HumanoidTier.TAG_PREFIX + "true_qi");
                    test.assertTrue(HumanoidTier.of(body(add(test, mob))) == HumanoidTier.MORTAL, "来源越权 " + reason);
                }
            }
            var unknown = spawn(test, EntityTypes.HUSK);
            test.assertTrue(HumanoidTier.of(body(unknown)) == HumanoidTier.MORTAL, "旧档未知来源抽档");
        } finally {
            UndeadFabric.reloadConfig(CONFIG);
            Files.deleteIfExists(alternate);
        }
        test.succeed();
    }

    static Zombie initialized(GameTestHelper test, EntitySpawnReason reason) {
        return initializedAt(test, reason, test.absolutePos(new BlockPos(SPAWN, SPAWN, SPAWN)));
    }

    private static Zombie initializedAt(GameTestHelper test, EntitySpawnReason reason, BlockPos position) {
        var level = test.getLevel();
        var mob = EntityTypes.HUSK.create(level, reason);
        mob.setPos(Vec3.atCenterOf(position));
        var adult = new ZombieGroupData(false /* isBaby */, false /* canSpawnJockey */);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(position), reason, adult);
        return mob;
    }

    @GameTest
    public void pendingRankSurvivesDiskMoveAndConfigReload(GameTestHelper test) throws Exception {
        Path alternate = Files.createTempFile("corpse-pending-", ".toml");
        try {
            force(alternate, HumanoidTier.WANDERING_QI);
            var origin = test.absolutePos(new BlockPos(SPAWN, SPAWN, SPAWN));
            var birthSite = origin.offset(REGION_OFFSET, 0, 0);
            var pending = initializedAt(test, EntitySpawnReason.NATURAL, birthSite);
            double birthDifficulty = test.getLevel().getCurrentDifficultyAt(birthSite).getEffectiveDifficulty();
            var saved = save(pending);
            test.assertTrue(!saved.contains(SAVE_KEY), "夹具提前建身");
            force(alternate, HumanoidTier.TRUE_QI);
            var restored = EntityTypes.HUSK.create(test.getLevel(), EntitySpawnReason.LOAD);
            load(restored, saved);
            var plan = ((HumanoidEntityAccess) restored).meridian$birthPlan();
            test.assertTrue(plan != null && plan.spawn().difficulty() == birthDifficulty
                    && plan.spawn().reason().equals("NATURAL")
                    && plan.spawn().entityType().equals("minecraft:husk") && plan.spawn().requested().isEmpty(),
                    "生成事实在建身前读盘时丢失");
            restored.setPos(Vec3.atCenterOf(origin));
            test.getLevel().addFreshEntity(restored);
            assertPearl(test, body(restored), BLACK_CAPACITY, BLACK_QI);
            test.assertTrue(((HumanoidEntityAccess) restored).meridian$birthPlan() == null, "建身后保留待生事实");
            checkReloadAndDimension(test, restored);
        } finally {
            UndeadFabric.reloadConfig(CONFIG);
            Files.deleteIfExists(alternate);
        }
        test.succeed();
    }

    private static final int REGION_OFFSET = 4096;

    private static final int CHUNK_TIMEOUT_TICKS = 1200;

    @GameTest(maxTicks = CHUNK_TIMEOUT_TICKS)
    public void realChunkUnloadRestoresTheSameHalfPearl(GameTestHelper test) {
        var world = test.getLevel();
        var away = test.absolutePos(new BlockPos(REGION_OFFSET, SPAWN, REGION_OFFSET));
        var chunk = ChunkPos.containing(away);
        var mob = commanded(test, EntityTypes.HUSK, HumanoidTier.TRUE_QI);
        mob.setNoAi(true /* noAi */);
        mob.setNoGravity(true /* noGravity */);
        var owner = mob.getUUID();
        world.setChunkForced(chunk.x(), chunk.z(), true /* forced */);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(world.isPositionEntityTicking(away), "目标区块尚未加载"))
                .thenExecute(() -> {
                    mob.setPos(Vec3.atCenterOf(away));
                    world.setChunkForced(chunk.x(), chunk.z(), false /* forced */);
                })
                .thenWaitUntil(() -> test.assertTrue(!world.areEntitiesLoaded(chunk.pack()) && mob.isRemoved(),
                        "远端区块仍有实体驻留"))
                .thenExecute(() -> world.setChunkForced(chunk.x(), chunk.z(), true /* forced */))
                .thenWaitUntil(() -> test.assertTrue(world.getEntity(owner) instanceof Zombie, "区块重载缺少原 UUID"))
                .thenExecute(() -> {
                    try {
                        var restored = (Zombie) world.getEntity(owner);
                        test.assertTrue(mob != restored, "原实体没有卸载");
                        assertPearl(test, body(restored), HAIRY_CAPACITY, HAIRY_QI);
                        restored.discard();
                    } finally {
                        world.setChunkForced(chunk.x(), chunk.z(), false /* forced */);
                    }
                }).thenSucceed();
    }

    @GameTest
    public void legacyWhiteBodyLoadsWithNoBirthFacts(GameTestHelper test) throws Exception {
        var alternate = Files.createTempFile("corpse-legacy-", ".toml");
        try {
            force(alternate, HumanoidTier.TRUE_QI);
            var old = spawn(test, EntityTypes.HUSK);
            var snapshot = body(old).toBuilder();
            for (var channel : snapshot.getStructureBuilder().getMeridiansBuilderList()) {
                var inherited = channel.getTagsList().stream().filter(tag -> !tag.startsWith("meridian_undead:"))
                        .toList();
                channel.clearTags()
                        .addAllTags(inherited);
            }
            var legacy = snapshot.build();
            var saved = save(old);
            saved.getCompound(SAVE_KEY).orElseThrow().putByteArray("body", legacy.toByteArray());
            old.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            var restored = EntityTypes.HUSK.create(test.getLevel(), EntitySpawnReason.LOAD);
            load(restored, saved);
            test.getLevel().addFreshEntity(restored);
            var actual = body(restored);
            test.assertTrue(actual.equals(legacy), "2.3 白僵存档被重生或改写");
            assertPearl(test, actual, WHITE_CAPACITY, 0);
        } finally {
            UndeadFabric.reloadConfig(CONFIG);
            Files.deleteIfExists(alternate);
        }
        test.succeed();
    }

    @GameTest
    public void sealedPearlAndNaturalBabyReceiveNoSecondGrant(GameTestHelper test) throws Exception {
        var alternate = Files.createTempFile("corpse-sealed-", ".toml");
        try {
            force(alternate, HumanoidTier.TRUE_QI);
            var zombie = add(test, initialized(test, EntitySpawnReason.NATURAL));
            var prior = body(zombie);
            assertPearl(test, prior, HAIRY_CAPACITY, HAIRY_QI);
            Files.writeString(alternate, "husk = false\n");
            UndeadFabric.reloadConfig(alternate);
            test.assertTrue(VanillaDamageFabric.inspectBody(zombie).isEmpty(), "关开关后封存身体仍可取");
            var saved = save(zombie);
            zombie.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            var restored = EntityTypes.HUSK.create(test.getLevel(), EntitySpawnReason.LOAD);
            load(restored, saved);
            test.getLevel().addFreshEntity(restored);
            force(alternate, HumanoidTier.TRUE_QI);
            test.assertTrue(body(restored).equals(prior), "封存读档后重发出生元");
            var level = test.getLevel();
            var baby = EntityTypes.HUSK.create(level, EntitySpawnReason.NATURAL);
            var group = new ZombieGroupData(true /* isBaby */, false /* canSpawnJockey */);
            baby.finalizeSpawn(level, level.getCurrentDifficultyAt(restored.blockPosition()),
                    EntitySpawnReason.NATURAL, group);
            add(test, baby);
            test.assertTrue(baby.isBaby() && VanillaDamageFabric.inspectBody(baby).isEmpty()
                    && !save(baby).contains(SAVE_KEY), "自然来源幼体获得尸身");
        } finally {
            UndeadFabric.reloadConfig(CONFIG);
            Files.deleteIfExists(alternate);
        }
        test.succeed();
    }

    private static void checkReloadAndDimension(GameTestHelper test, Zombie original) {
        var prior = body(original);
        var saved = save(original);
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        var restored = EntityTypes.HUSK.create(test.getLevel(), EntitySpawnReason.LOAD);
        load(restored, saved);
        test.getLevel().addFreshEntity(restored);
        test.assertTrue(body(restored).equals(prior), "卸载并读回后尸丹或身体改变");
        var nether = test.getLevel().getServer().getLevel(Level.NETHER);
        var transition = new TeleportTransition(nether, restored.position(), Vec3.ZERO, 0, 0,
                TeleportTransition.DO_NOTHING);
        var moved = (Zombie) restored.teleport(transition);
        test.assertTrue(moved != null && moved.level() == nether, "尸身未进入下界");
        var returnTrip = new TeleportTransition(test.getLevel(), original.position(), Vec3.ZERO, 0, 0,
                TeleportTransition.DO_NOTHING);
        var returned = (Zombie) moved.teleport(returnTrip);
        test.assertTrue(returned != null && body(returned).equals(prior), "跨维度丢失已建身体");
        returned.discard();
    }
}
