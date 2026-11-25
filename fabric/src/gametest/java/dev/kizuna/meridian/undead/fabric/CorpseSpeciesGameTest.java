package dev.kizuna.meridian.undead.fabric;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.*;

import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.HealthMessages.DamageKind;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueState;
import dev.kizuna.meridian.undead.server.UndeadContent;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;

public final class CorpseSpeciesGameTest {
    static final List<EntityType<? extends Zombie>> TYPES = List.of(EntityTypes.ZOMBIE, EntityTypes.HUSK,
            EntityTypes.DROWNED, EntityTypes.ZOMBIE_VILLAGER);
    private static final int ORGANS = 16;
    private static final int WOUND = 5;
    private static final int AI_TICKS = 20;

    @GameTest
    public void adultsAreWhiteCorpses(GameTestHelper test) {
        for (var type : TYPES) {
            var zombie = spawn(test, type);
            var species = HumanoidSpecies.fromEntityId("minecraft:" + UndeadFabric.SPECIES.get(type)).orElseThrow();
            test.assertTrue(!species.brainManaged(), "尸身接管了原版大脑");
            var fresh = body(zombie);
            assertFresh(test, fresh);
            test.assertTrue(fresh.getId().endsWith(zombie.getStringUUID()), "新尸身身份不符");
            String report = UndeadInspect.describe(fresh);
            for (var part : fresh.getStructure().getPartsList()) {
                test.assertTrue(report.contains(part.getDisplayName()), "inspect 缺部位 " + part.getId());
            }
            test.assertTrue(report.contains("尸丹"), "inspect 缺尸丹");
        }
        test.succeed();
    }

    static void assertFresh(GameTestHelper test, BodySnapshot fresh) {
        test.assertTrue(fresh.getTemplate().getId().equals(UndeadContent.TEMPLATE_ID), "没有使用尸身");
        test.assertTrue(fresh.getOrganStatesCount() == ORGANS, "出生未写齐十六个器官");
        test.assertTrue(fresh.getOrganStatesList().stream().anyMatch(s -> s.getId().equals("corpse_pearl")),
                "出生漏了尸丹");
        for (var organ : fresh.getOrganStatesList()) {
            test.assertTrue(organ.hasBuffer() && organ.getBuffer().getEntriesCount() == 0, "器官缓存非显式空");
        }
        var structure = fresh.getStructure();
        for (var meridian : fresh.getMeridianStatesList()) {
            test.assertTrue(meridian.getOpening().getStage() == MeridianOpeningStage.UNOPENED
                    && meridian.getLumenQi().getEntriesCount() == 0 && meridian.getWallQi().getEntriesCount() == 0,
                    "白僵出生已开脉或有天然库存");
        }
        for (var part : structure.getPartsList()) {
            test.assertTrue(fresh.getPartStatesList().stream().anyMatch(s -> s.getId().equals(part.getId())
                    && s.getHp() == part.getMaxHp()), "出生部位未满血 " + part.getId());
        }
        test.assertTrue(!fresh.containsAddonProgress("meridian_technique:state"), "出生获授了功法进度");
    }

    @GameTest
    public void vanillaAiNeverStartsInnateGuard(GameTestHelper test) {
        // 给予真实敌对目标，让宿主在原版 AI 运行时查询天生式和学习记录。
        var zombie = spawn(test, EntityTypes.HUSK);
        var victim = test.spawn(EntityTypes.VILLAGER, SPAWN, SPAWN, SPAWN);
        zombie.setTarget(victim);
        var known = TechniqueFabric.service().known(zombie.getUUID());
        test.assertTrue(known.stream().anyMatch(entry -> entry.technique().getId()
                .equals("meridian_basic_techniques:guard")), "类人天生格挡不可查");
        test.runAfterDelay(AI_TICKS, () -> {
            var progress = TechniqueState.read(BodyProtobuf.fromMessage(body(zombie)));
            test.assertTrue(progress.getKnownCount() == 0, "原版 AI 获授了学习记录");
            test.assertTrue(!progress.hasActiveCast() && !progress.hasLastCast(), "原版 AI 主动起式");
            test.succeed();
        });
    }

    @GameTest
    public void inspectCommandCompletesAndResolvesSelector(GameTestHelper test) {
        var zombie = spawn(test, EntityTypes.ZOMBIE);
        var server = test.getLevel().getServer();
        var source = server.createCommandSourceStack().withLevel(test.getLevel());
        var commands = server.getCommands();
        var dispatcher = commands.getDispatcher();
        String branch = "meridian body undead ";
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(branch, source)).join();
        test.assertTrue(suggestions.getList().stream().anyMatch(s -> s.getText().equals("inspect")),
                "inspect 没有补全");
        int[] result = {0};
        commands.performPrefixedCommand(source.withCallback((success, value) -> result[0] = value),
                branch + "inspect " + zombie.getStringUUID());
        test.assertTrue(result[0] == 1, "选择器未读到尸身");
        test.succeed();
    }

    @GameTest
    public void babiesStayVanilla(GameTestHelper test) {
        for (var type : TYPES) {
            var baby = type.create(test.getLevel(), EntitySpawnReason.COMMAND);
            baby.setBaby(true /* baby */);
            add(test, baby);
            test.assertTrue(VanillaDamageFabric.inspectBody(baby).isEmpty(), "幼体被认领 " + type);
            test.assertTrue(!save(baby).contains(SAVE_KEY), "幼体写入了身体存档");
            baby.setHealth(1);
            test.assertTrue(baby.getHealth() == 1, "幼体原版血量被身体覆盖");
        }
        test.succeed();
    }

    @GameTest
    public void partialConfigUsesPackagedSwitchesAndRejectsUnknownKeys(GameTestHelper test) throws Exception {
        // 通过与重载共用的读盘入口检验旧文件，避免改动服务器正在运行的物种状态。
        Path file = Files.createTempFile("corpse-switches-", ".toml");
        try {
            String partial = "zombie = false\nhusk = false\n";
            Files.writeString(file, partial);
            var enabled = CorpseConfig.load(file).enabled();
            var expected = Set.of(EntityTypes.DROWNED, EntityTypes.ZOMBIE_VILLAGER, EntityTypes.ZOMBIFIED_PIGLIN);
            test.assertTrue(enabled.equals(expected),
                    "缺少的尸身开关应采用随包默认值");
            test.assertTrue(Files.readString(file).equals(partial), "读取时改写了旧配置");
            Files.writeString(file, partial + "unlisted = true\n");
            try {
                CorpseConfig.load(file);
                test.fail("接受了未知尸身开关");
            } catch (IllegalArgumentException error) {
                test.assertTrue(error.getMessage().contains("unlisted"), "报错缺少未知键名");
            }
        } finally {
            Files.deleteIfExists(file);
        }
        test.succeed();
    }

    @GameTest
    public void switchesSealOnlyTheirSpecies(GameTestHelper test) throws Exception {
        Path installed = FabricLoader.getInstance().getConfigDir().resolve("meridian-undead.toml");
        String original = Files.readString(installed);
        Path alternate = Files.createTempFile(installed.getParent(), "species-test-", ".toml");
        try {
            for (var type : TYPES) {
                var wounded = spawn(test, type);
                var prior = damage(wounded, part("left_foot"), WOUND, DamageKind.CUSTOM);
                String key = UndeadFabric.SPECIES.get(type);
                Files.writeString(alternate, original.replace(key + " = true", key + " = false"));
                UndeadFabric.reloadConfig(alternate);
                test.assertTrue(VanillaDamageFabric.inspectBody(wounded).isEmpty(),
                        "关闭开关后仍能查询身体");
                test.assertTrue(save(wounded).contains(SAVE_KEY), "关闭开关后丢了存档键");
                assertNewClaims(test, type);
                UndeadFabric.reloadConfig(installed);
                test.assertTrue(body(wounded).equals(prior), "再次认领丢失原身体或伤势");
            }
        } finally {
            UndeadFabric.reloadConfig(installed);
            Files.deleteIfExists(alternate);
        }
        test.succeed();
    }

    private static void assertNewClaims(GameTestHelper test, EntityType<? extends Zombie> disabled) {
        for (var type : TYPES) {
            var fresh = spawn(test, type);
            boolean claimed = VanillaDamageFabric.inspectBody(fresh).isPresent();
            test.assertTrue(claimed == (type != disabled), "物种开关串扰 " + type);
            test.assertTrue(save(fresh).contains(SAVE_KEY) == claimed, "新实体多写或漏写存档键");
        }
    }

    @GameTest
    public void woundedBodySurvivesEntityReload(GameTestHelper test) {
        var mob = spawn(test, EntityTypes.HUSK);
        var wounded = damage(mob, part("abdomen"), WOUND, DamageKind.CUSTOM);
        var saved = save(mob);
        mob.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        test.assertTrue(!((HumanoidEntityAccess) mob).meridian$humanoidReleaseQueued(), "卸载错误释放身体");
        var restored = EntityTypes.HUSK.create(test.getLevel(), EntitySpawnReason.LOAD);
        load(restored, saved);
        test.getLevel().addFreshEntity(restored);
        test.assertTrue(body(restored).equals(wounded), "带伤读档后完整快照不符");
        test.succeed();
    }
}
