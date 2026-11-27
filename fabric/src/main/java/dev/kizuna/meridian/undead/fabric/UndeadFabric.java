package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.human.server.HumanDamage;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeciesBirths;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpeciesHooks;
import dev.kizuna.meridian.humanoid.fabric.HumanoidSpawns;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.SpeciesBodyRules;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyTemplate;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueStatusBenefits;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueAddonCostEntrypoint;
import dev.kizuna.meridian.technique.server.TechniqueAddonCostPayers;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.undead.server.CorpseBody;
import dev.kizuna.meridian.undead.server.CorpseRank;
import dev.kizuna.meridian.undead.server.UndeadContent;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageTypeTags;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.Zombie.ZombieGroupData;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

public final class UndeadFabric implements ModInitializer, TechniqueAddonCostEntrypoint {
    static final Map<EntityType<?>, String> SPECIES = Map.of(EntityTypes.ZOMBIE, "zombie",
            EntityTypes.HUSK, "husk", EntityTypes.DROWNED, "drowned", EntityTypes.ZOMBIE_VILLAGER, "zombie_villager",
            EntityTypes.ZOMBIFIED_PIGLIN, "zombified_piglin");
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
    private static final String BRONZE_HIDE_ID = "meridian_undead:bronze_hide";
    private static final double BRONZE_HIDE_PROTECTION = 0.6;
    private static final float MIN_DIRECT_SUNLIGHT_BRIGHTNESS = 0.5F;
    private static final long BRONZE_HIDE_HIT_WINDOW_TICKS = 60;
    private static CorpseConfig config;
    private static BodyTemplate template;
    private static MinecraftServer currentServer;
    private static final Map<UUID, Long> LAST_PROTECTED_HITS = new HashMap<>();

    @Override
    public void registerPayers(TechniqueAddonCostPayers payers) {
        // 尸身照常绑定已开经脉，起手所需的真元由尸丹提供。
        payers.registerQiPaymentOrgan(UndeadContent.TEMPLATE_ID, UndeadContent.PEARL);
    }

    @Override
    public void onInitialize() {
        // 成年尸身共用图纸、出生工厂和死亡裁决。
        Path path = FabricLoader.getInstance().getConfigDir().resolve("meridian-undead.toml");
        reloadConfig(path);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> currentServer = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> currentServer = null);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_PROTECTED_HITS.clear());
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> LAST_PROTECTED_HITS.remove(entity.getUUID()));
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
        UndeadSpawnEggs.register(species.stream()
                .filter(entry -> !entry.entityId().equals("minecraft:zombified_piglin")).toList());
        registerZombification();
        HumanDamage.registerTemplateDefaults(UndeadContent.TEMPLATE_ID, HumanContent.TEMPLATE_ID);
        VanillaDamageFabric.registerTemplateDefaults(UndeadContent.TEMPLATE_ID, HumanContent.TEMPLATE_ID);
        VanillaDamageFabric.registerDamageProtection(UndeadFabric::protectedDamageShare);
        VanillaDamageFabric.registerDamageObserver(UndeadFabric::observeDamage);
        TechniqueFabric.SERVICE_STARTED.register(service -> service.addCastCheck(UndeadFabric::checkCast));
        UndeadInspect.register();
        UndeadLeapFabric.initialize();
        NightVisits.register();
    }

    private static void registerZombification() {
        ServerLivingEntityEvents.MOB_CONVERSION.register((previous, next, params) -> {
            // 原版完成装备交接之后，以旧身档位重建尸身和尸丹，学卷只看新身带来的剑。
            if (previous instanceof AbstractPiglin
                    && next.getType() == EntityTypes.ZOMBIFIED_PIGLIN && accepts(next)) {
                VanillaDamageFabric.inspectBody(previous).map(HumanoidTier::of)
                        .ifPresent(tier -> HumanoidSpeciesBirths.birth(next, tier));
            }
        });
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

    private static void observeDamage(VanillaDamageFabric.DamageEvent event) {
        if (event.input().damageAfterEffects() <= 0 || !isProtectedSource(event.source())
                || event.bodyDead()
                || !(event.target().level() instanceof ServerLevel level)
                || !accepts(event.target())) {
            return;
        }
        LAST_PROTECTED_HITS.put(event.target().getUUID(), level.getGameTime());
    }

    public static void tryBronzeHide(Zombie corpse, ServerLevel world) {
        if (!hasRecentProtectedHit(corpse.getUUID(), world.getGameTime())) {
            return;
        }
        TechniqueService service = TechniqueFabric.service();
        Optional<CastRequest> request = bronzeHideRequest(corpse, world, service);
        if (request.isEmpty()) {
            return;
        }
        service.cast(corpse.getUUID(), request.get());
    }

    static boolean shouldPrioritizeBronzeHide(Zombie corpse, ServerLevel world) {
        if (!hasRecentProtectedHit(corpse.getUUID(), world.getGameTime())
                || !canAttemptBronzeHide(corpse, world)
                || corpse.hasEffect(TechniqueStatusBenefits.get(BRONZE_HIDE_ID).effect())) {
            return false;
        }
        TechniqueService service = TechniqueFabric.service();
        return service.known(corpse.getUUID()).stream()
                .anyMatch(entry -> entry.technique().getId().equals(BRONZE_HIDE_ID));
    }

    private static boolean hasRecentProtectedHit(UUID entityId, long gameTime) {
        Long hitTick = LAST_PROTECTED_HITS.get(entityId);
        if (hitTick == null) {
            return false;
        }
        if (gameTime - hitTick > BRONZE_HIDE_HIT_WINDOW_TICKS) {
            LAST_PROTECTED_HITS.remove(entityId, hitTick);
            return false;
        }
        return true;
    }

    private static Optional<CastRequest> bronzeHideRequest(Zombie corpse, ServerLevel world,
                                                           TechniqueService service) {
        if (!canAttemptBronzeHide(corpse, world)) {
            return Optional.empty();
        }
        boolean known = service.known(corpse.getUUID()).stream()
                .anyMatch(entry -> entry.technique().getId().equals(BRONZE_HIDE_ID));
        if (!known || corpse.hasEffect(TechniqueStatusBenefits.get(BRONZE_HIDE_ID).effect())
                || service.observe(corpse.getUUID()).flatMap(TechniqueService.Observation::cast).isPresent()) {
            return Optional.empty();
        }
        var body = VanillaDamageFabric.inspectBody(corpse).orElse(null);
        var definition = service.registry().findLatest(BRONZE_HIDE_ID).orElse(null);
        if (body == null || definition == null) {
            return Optional.empty();
        }
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        var request = CastRequest.newBuilder()
                .setBodyId(body.getId())
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
        return Optional.of(request);
    }

    private static boolean canAttemptBronzeHide(Zombie corpse, ServerLevel world) {
        if (!corpse.isAlive() || !accepts(corpse) || !TechniqueFabric.running() || corpse.isOnFire()) {
            return false;
        }
        if (corpse.getType() != EntityTypes.HUSK && isDirectSunlight(corpse)) {
            return false;
        }
        var target = corpse.getTarget();
        return target != null && target.isAlive() && target.level() == world;
    }

    private static boolean isProtectedSource(DamageSource source) {
        return source.is(VanillaDamageTypeTags.MELEE)
                || (source.is(DamageTypeTags.IS_PROJECTILE) && source.getDirectEntity() instanceof AbstractArrow);
    }

    static OptionalDouble protectedDamageShare(LivingEntity target, DamageSource source, BodySnapshot body) {
        if (!accepts(target) || !isProtectedSource(source)) {
            return OptionalDouble.empty();
        }
        var active = TechniqueStatusBenefits.activePlain(target);
        if (active.isEmpty() || !active.get().benefit().getId().equals(BRONZE_HIDE_ID)) {
            return OptionalDouble.empty();
        }
        var pearlDefinition = body.getStructure().getOrgansList().stream()
                .filter(organ -> organ.getId().equals(UndeadContent.PEARL))
                .findFirst()
                .orElse(null);
        if (pearlDefinition == null) {
            return OptionalDouble.empty();
        }
        double pearlMaxHp = pearlDefinition.getMaxHp();
        if (!Double.isFinite(pearlMaxHp) || pearlMaxHp <= 0) {
            return OptionalDouble.empty();
        }
        var pearlState = body.getOrganStatesList().stream()
                .filter(organ -> organ.getId().equals(UndeadContent.PEARL))
                .findFirst()
                .orElse(null);
        if (pearlState == null) {
            return OptionalDouble.empty();
        }
        double pearlHp = pearlState.getHp();
        if (!Double.isFinite(pearlHp)) {
            return OptionalDouble.empty();
        }
        double boundedPearlHp = Math.max(0, Math.min(pearlHp, pearlMaxHp));
        double pearlRatio = boundedPearlHp / pearlMaxHp;
        return OptionalDouble.of(BRONZE_HIDE_PROTECTION * pearlRatio);
    }

    private static Optional<String> checkCast(UUID entityUuid, CastRequest request) {
        if (!request.getTechnique().getId().equals(BRONZE_HIDE_ID)) {
            return Optional.empty();
        }
        MinecraftServer server = currentServer;
        if (server == null) {
            return Optional.empty();
        }
        for (ServerLevel level : server.getAllLevels()) {
            var found = level.getEntity(entityUuid);
            if (found == null) {
                continue;
            }
            if (!(found instanceof LivingEntity entity)
                    || !SPECIES.containsKey(entity.getType())
                    || !accepts(entity)) {
                return Optional.empty();
            }
            if (entity.getType() != EntityTypes.HUSK && isDirectSunlight(entity)) {
                return Optional.of("日光下不可起式");
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean isDirectSunlight(LivingEntity entity) {
        if (entity.level().isClientSide()) {
            return false;
        }
        boolean monstersBurn = entity.level().environmentAttributes()
                .getValue(EnvironmentAttributes.MONSTERS_BURN, entity.position());
        if (!monstersBurn) {
            return false;
        }
        if (entity.getLightLevelDependentMagicValue() <= MIN_DIRECT_SUNLIGHT_BRIGHTNESS) {
            return false;
        }
        if (entity.isInWaterOrRain() || entity.isInPowderSnow || entity.wasInPowderSnow) {
            return false;
        }
        if (!entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            return false;
        }
        BlockPos eyePosition = BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ());
        return entity.level().canSeeSky(eyePosition);
    }
}
