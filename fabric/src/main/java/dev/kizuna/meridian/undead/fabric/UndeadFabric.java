package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeciesHooks;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpawns;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.SpeciesBodyRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyTemplate;
import dev.kizuna.meridian.undead.server.CorpseBody;
import dev.kizuna.meridian.undead.server.CorpseRank;
import dev.kizuna.meridian.undead.server.UndeadContent;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie.ZombieGroupData;

public final class UndeadFabric implements ModInitializer {
    static final Map<EntityType<?>, String> SPECIES = Map.of(EntityTypes.ZOMBIE, "zombie",
            EntityTypes.HUSK, "husk", EntityTypes.DROWNED, "drowned", EntityTypes.ZOMBIE_VILLAGER, "zombie_villager");
    private static final String ENTRY = """
            [species."minecraft:%1$s"]
            template = "%2$s"
            default_tier = "mortal"
            brain = false
            [species."minecraft:%1$s".profile]
            true_qi_base_probability = 0.0
            opening_count_multiplier = 1.0
            fill_multiplier = 1.0
            wall_fill_multiplier = 1.0
            arm_opening_weight = 1.0
            foot_opening_weight = 1.0
            """;
    private static CorpseConfig config;
    private static BodyTemplate template;

    @Override
    public void onInitialize() {
        // 四种实体使用同一修订的图纸、出生工厂和死亡裁决。
        Path path = FabricLoader.getInstance().getConfigDir().resolve("meridian-undead.toml");
        reloadConfig(path);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                reloadConfig(path);
            }
        });
        var bundle = UndeadContent.bundle();
        var rules = new SpeciesBodyRules(
                request -> CorpseBody.born(template, request.bodyId(), request.now(), rank(request.tier()),
                        config.physiology()),
                (wounded, human) -> CorpseBody.lethal(wounded),
                (spawn, random) -> config.chances().choose(spawn, random),
                saved -> config.physiology().restore(saved, template));
        String table = SPECIES.values().stream().sorted()
                .map(id -> ENTRY.formatted(id, UndeadContent.TEMPLATE_ID))
                .collect(Collectors.joining("\n"));
        var species = HumanoidSpeciesHooks.registerAll(table, rules, UndeadFabric::accepts, List.of(bundle));
        species.forEach(entry -> HumanoidSpawns.registerCommandGroup(entry,
                () -> new ZombieGroupData(false /* isBaby */, false /* canSpawnJockey */)));
        UndeadSpawnEggs.register(species);
        HumanDamage.registerTemplateDefaults(UndeadContent.TEMPLATE_ID, HumanContent.TEMPLATE_ID);
        VanillaDamageFabric.registerTemplateDefaults(UndeadContent.TEMPLATE_ID, HumanContent.TEMPLATE_ID);
        UndeadInspect.register();
    }

    public static boolean accepts(LivingEntity entity) {
        // 开关按精确实体类型查，幼体的判断每次在当前实体上执行。
        return !entity.isBaby() && config.enabled().contains(entity.getType());
    }

    static void reloadConfig(Path path) {
        var next = CorpseConfig.load(path);
        var nextTemplate = UndeadContent.bundle(next.physiology()).getTemplates(0);
        config = next;
        template = nextTemplate;
    }

    public static double healingMultiplier() {
        return config.physiology().healing();
    }

    static CorpseRank rank(HumanoidTier tier) {
        return switch (tier) {
            case MORTAL -> CorpseRank.WHITE;
            case WANDERING_QI -> CorpseRank.BLACK;
            case TRUE_QI -> CorpseRank.HAIRY;
        };
    }
}
