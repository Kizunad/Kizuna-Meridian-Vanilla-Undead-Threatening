package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.world.TechniqueLeaps;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.LeapProfile;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.technique.server.TechniqueCastEndReason;
import dev.kizuna.meridian.technique.server.TechniqueCastEnded;
import dev.kizuna.meridian.technique.server.TechniqueKnowledge;
import dev.kizuna.meridian.technique.server.TechniqueLeap;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import dev.kizuna.meridian.vanilla.fabric.VanillaStrikeContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;

/** 尸身纵的服务端施放、定向飞行和尸种 AI。 */
public final class UndeadLeapFabric {
    private static final String LEAP_ID = "meridian_undead:leap";
    private static final double DIRECTION_EPSILON = 0.000001;
    private static final double CONTACT_DAMAGE_MULTIPLIER = 1.2;
    private static final double CONTACT_VERTICAL_KNOCKBACK = 0.1;
    private static final double DEFAULT_ATTACK_DAMAGE = 1;
    private static final float MIN_DIRECT_SUNLIGHT_BRIGHTNESS = 0.5F;
    private static final long HIT_STAGGER_MILLIS = 200;
    private static final double HIT_STAGGER_MOVEMENT = 0.5;
    private static final int MISS_RECOVERY_TICKS = 20;
    private static final AttachmentType<TierAtRevision> TIER = AttachmentRegistry.create(
            Identifier.parse("meridian_undead:leap_tier"));
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Long> NEXT_DECISION = new HashMap<>();
    private static final Map<UUID, Long> MISS_RECOVERY_END = new HashMap<>();
    private static final Set<String> CONTACTED_CASTS = new HashSet<>();
    private static MinecraftServer currentServer;

    private record TierAtRevision(String bodyId, long revision, HumanoidTier tier) { }

    private record Pending(String castId, LeapProfile profile, Vec3 direction, double distance,
                           TechniqueLeap.Landing landing) { }

    private record DirectionPlan(Vec3 direction, double distance) { }

    private UndeadLeapFabric() {
        // 纵的飞行状态由共享功法库挂在实体上，本类只保留起手到完成之间的身份。
    }

    public static void initialize() {
        TechniqueLeaps.addDirector(UndeadLeapFabric::directs);
        TechniqueFabric.SERVICE_STARTED.register(UndeadLeapFabric::serviceStarted);
        TechniqueFabric.CAST_OBSERVED.register(UndeadLeapFabric::castObserved);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> currentServer = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> unload(entity));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> died(entity));
        ServerLivingEntityEvents.AFTER_DAMAGE.register(UndeadLeapFabric::staggerAfterDamage);
    }

    private static void serviceStarted(TechniqueService service) {
        service.addCastCheck(UndeadLeapFabric::checkCast);
        service.addCastEndListener(UndeadLeapFabric::castEnded);
    }

    private static void stop() {
        PENDING.clear();
        NEXT_DECISION.clear();
        MISS_RECOVERY_END.clear();
        CONTACTED_CASTS.clear();
        currentServer = null;
    }

    private static void unload(Entity entity) {
        if (entity instanceof LivingEntity living) {
            if (TechniqueFabric.running()) {
                TechniqueFabric.service().cancelOnUnload(entity.getUUID());
            }
            TechniqueLeaps.cancel(living);
            PENDING.remove(entity.getUUID());
            NEXT_DECISION.remove(entity.getUUID());
            MISS_RECOVERY_END.remove(entity.getUUID());
            TechniqueLeaps.unlockDirectedActions(living);
        }
    }

    private static void died(LivingEntity entity) {
        if (TechniqueFabric.running()) {
            TechniqueFabric.service().cancelOnDeath(entity.getUUID());
        }
        TechniqueLeaps.cancel(entity);
        PENDING.remove(entity.getUUID());
        NEXT_DECISION.remove(entity.getUUID());
        MISS_RECOVERY_END.remove(entity.getUUID());
        TechniqueLeaps.unlockDirectedActions(entity);
    }

    public static void tickZombie(Zombie mob, ServerLevel level) {
        UUID entityId = mob.getUUID();
        if (recovering(mob) || PENDING.containsKey(entityId)) {
            mob.getNavigation().stop();
            return;
        }
        LivingEntity target = mob.getTarget();
        Long nextDecision = NEXT_DECISION.get(entityId);
        if (target == null || !target.isAlive() || target.level() != level
                || nextDecision != null && level.getGameTime() < nextDecision) {
            return;
        }
        if (!mob.isAlive() || !mob.onGround() || mob.isNoAi() || mob.isInWater() || sunlightBlocksLeap(mob)
                || !TechniqueFabric.running() || !directs(mob) || UndeadFabric.shouldPrioritizeBronzeHide(mob, level)) {
            return;
        }
        TechniqueService service = TechniqueFabric.service();
        var registry = service.registry();
        TechniqueDefinition definition = registry.findLatest(LEAP_ID).orElse(null);
        if (definition == null) {
            return;
        }
        LeapProfile profile = leapProfile(definition).orElse(null);
        if (profile == null || horizontalDistance(mob, target) < profile.getAiMinimumDistance()) {
            return;
        }
        var observed = service.observe(entityId).orElse(null);
        if (observed == null || observed.cast().isPresent()) {
            return;
        }
        var reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        var body = observed.body();
        if (TechniqueKnowledge.contains(registry, body, reference)) {
            CastRequest request = request(body.id().value(), reference);
            startAiLeap(mob, service, target, request, profile);
        }
    }

    private static void startAiLeap(Mob mob, TechniqueService service, LivingEntity target,
                                    CastRequest request, LeapProfile profile) {
        UUID entityId = mob.getUUID();
        long gameTime = mob.level().getGameTime();
        NEXT_DECISION.put(entityId, gameTime + profile.getAiDecisionTicks());
        Optional<Pending> plan = plan(mob, profile, target.position(), request.getCastId());
        if (plan.isEmpty()) {
            return;
        }
        Pending pending = plan.orElseThrow();
        PENDING.put(entityId, pending);
        TechniqueLeaps.lockDirectedActions(mob);
        CastOutcome outcome = service.cast(entityId, request);
        if (failed(outcome)) {
            clearPending(entityId, pending);
            return;
        }
        mob.getNavigation().stop();
    }

    private static void staggerAfterDamage(LivingEntity target, DamageSource source, float dealt,
                                            float taken, boolean blocked) {
        if (blocked || taken <= 0 || !(target instanceof Zombie)) {
            return;
        }
        TechniqueService service = TechniqueFabric.service();
        boolean pendingLeap = PENDING.containsKey(target.getUUID());
        boolean activeCast = service != null && service.observe(target.getUUID())
                .flatMap(TechniqueService.Observation::cast)
                .filter(forecast -> forecast.interruptible())
                .isPresent();
        if (service != null && (pendingLeap || activeCast)) {
            service.stagger(target.getUUID(),
                    new TechniqueService.Stagger(HIT_STAGGER_MILLIS, HIT_STAGGER_MOVEMENT));
        }
    }

    private static CastRequest request(String bodyId, TechniqueReference reference) {
        return CastRequest.newBuilder()
                .setBodyId(bodyId)
                .setTechnique(reference)
                .setCastId(UUID.randomUUID().toString())
                .build();
    }

    private static Optional<String> checkCast(UUID entityId, CastRequest request) {
        if (!LEAP_ID.equals(request.getTechnique().getId())) {
            return Optional.empty();
        }
        LivingEntity actor = findEntity(entityId);
        if (!(actor instanceof Zombie corpse) || !UndeadFabric.accepts(corpse)) {
            return Optional.empty();
        }
        HumanoidTier tier = tier(corpse);
        if (tier == HumanoidTier.MORTAL) {
            return Optional.empty();
        }
        if (actor.getType() == EntityTypes.DROWNED && actor.isInWater()) {
            return Optional.of("水中不可起纵");
        }
        if (sunlightBlocksLeap(actor)) {
            return Optional.of("日光下不可起纵");
        }
        Pending existing = PENDING.get(entityId);
        if (existing != null && existing.castId().equals(request.getCastId())) {
            return Optional.empty();
        }
        TechniqueDefinition definition = TechniqueFabric.service().registry().find(request.getTechnique()).orElse(null);
        if (definition == null) {
            return Optional.empty();
        }
        LeapProfile profile = leapProfile(definition).orElse(null);
        if (profile == null) {
            return Optional.of("纵的定义不完整");
        }
        Vec3 target = targetPosition(actor);
        Optional<Pending> plan = plan(actor, profile, target, request.getCastId());
        if (plan.isEmpty()) {
            return Optional.of("纵前方没有可落脚处");
        }
        PENDING.put(entityId, plan.orElseThrow());
        TechniqueLeaps.lockDirectedActions(actor);
        return Optional.empty();
    }

    private static Vec3 targetPosition(LivingEntity actor) {
        LivingEntity target = null;
        if (actor instanceof Mob mob) {
            target = mob.getTarget();
        }
        if (target != null && target.isAlive() && target.level() == actor.level()) {
            return target.position();
        }
        Vec3 look = Vec3.directionFromRotation(0, actor.getYRot());
        return actor.position().add(look.x, 0, look.z);
    }

    private static Optional<Pending> plan(LivingEntity actor, LeapProfile profile, Vec3 target, String castId) {
        DirectionPlan directionPlan = direction(actor, target, profile.getMaximumPath());
        Optional<TechniqueLeap.Landing> landing = TechniqueLeaps.planDirected(
                actor, profile, directionPlan.direction(), directionPlan.distance());
        return landing.map(value -> new Pending(castId, profile, directionPlan.direction(), directionPlan.distance(),
                value));
    }

    private static DirectionPlan direction(LivingEntity actor, Vec3 target, double maximumPath) {
        Vec3 horizontal = new Vec3(target.x - actor.getX(), 0, target.z - actor.getZ());
        double length = horizontal.length();
        if (!Double.isFinite(length) || length < DIRECTION_EPSILON) {
            Vec3 look = Vec3.directionFromRotation(0, actor.getYRot());
            horizontal = new Vec3(look.x, 0, look.z);
            length = horizontal.length();
        }
        Vec3 direction = horizontal.scale(1 / length);
        double distance = Math.min(length, maximumPath);
        return new DirectionPlan(direction, distance);
    }

    private static Optional<LeapProfile> leapProfile(TechniqueDefinition definition) {
        return definition.getEffectsList().stream()
                .filter(effect -> effect.hasStatusBenefit()
                        && LEAP_ID.equals(effect.getStatusBenefit().getId())
                        && effect.getStatusBenefit().hasLeap())
                .map(effect -> effect.getStatusBenefit().getLeap())
                .findFirst();
    }

    private static void castObserved(UUID entityId, CastOutcome outcome) {
        if (!failed(outcome)) {
            return;
        }
        Pending pending = PENDING.get(entityId);
        if (pending != null && pending.castId().equals(outcome.getCastId())) {
            clearPending(entityId, pending);
        }
    }

    private static boolean failed(CastOutcome outcome) {
        return outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED;
    }

    private static void castEnded(TechniqueCastEnded event) {
        Pending pending = PENDING.get(event.entityUuid());
        if (event.reason() != TechniqueCastEndReason.COMPLETED) {
            if (pending != null && pending.castId().equals(event.castId())) {
                clearPending(event.entityUuid(), pending);
            }
            return;
        }
        if (pending == null || !pending.castId().equals(event.castId())) {
            return;
        }
        LivingEntity actor = findEntity(event.entityUuid());
        if (actor == null) {
            clearPending(event.entityUuid(), pending);
            return;
        }
        TechniqueLeaps.DirectedLeap leap = new TechniqueLeaps.DirectedLeap(pending.profile(), LEAP_ID,
                pending.direction(), pending.distance(), pending.landing(), pending.castId(),
                result -> completed(event.entityUuid(), event.castId(), result),
                target -> contact(event.entityUuid(), event.castId(), target));
        TechniqueLeaps.Jump started = TechniqueLeaps.startDirected(actor, leap);
        if (started != TechniqueLeaps.Jump.LEAP) {
            clearPending(event.entityUuid(), pending);
            return;
        }
        if (actor instanceof Mob mob) {
            mob.getNavigation().stop();
        }
    }

    private static void completed(UUID entityId, String castId, TechniqueLeaps.LeapResult result) {
        Pending pending = PENDING.get(entityId);
        if (pending != null && pending.castId().equals(castId)) {
            PENDING.remove(entityId, pending);
            LivingEntity actor = findEntity(entityId);
            boolean missed = result != TechniqueLeaps.LeapResult.LANDED || !CONTACTED_CASTS.remove(castId);
            if (missed && actor instanceof Mob mob) {
                MISS_RECOVERY_END.put(entityId, mob.level().getGameTime() + MISS_RECOVERY_TICKS);
                mob.getNavigation().stop();
            } else if (actor != null) {
                TechniqueLeaps.unlockDirectedActions(actor);
            }
        }
    }

    private static void contact(UUID entityId, String castId, LivingEntity target) {
        Pending pending = PENDING.get(entityId);
        LivingEntity actor = findEntity(entityId);
        if (pending == null || !pending.castId().equals(castId) || actor == null || !target.isAlive()
                || !(actor.level() instanceof ServerLevel level)) {
            return;
        }
        CONTACTED_CASTS.add(castId);
        DamageSource source = actor.damageSources().mobAttack(actor);
        double rawBaseDamage = actor.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double baseDamage = rawBaseDamage;
        if (!Double.isFinite(rawBaseDamage) || rawBaseDamage <= 0) {
            baseDamage = DEFAULT_ATTACK_DAMAGE;
        }
        float damage = (float) (baseDamage * CONTACT_DAMAGE_MULTIPLIER);
        try (VanillaStrikeContext ignored = VanillaStrikeContext.attack(actor, source)) {
            target.hurtServer(level, source, damage);
        }
        target.push(pending.direction().x, CONTACT_VERTICAL_KNOCKBACK, pending.direction().z);
    }

    private static void clearPending(UUID entityId, Pending pending) {
        if (!PENDING.remove(entityId, pending)) {
            return;
        }
        CONTACTED_CASTS.remove(pending.castId());
        LivingEntity actor = findEntity(entityId);
        if (actor != null) {
            TechniqueLeaps.unlockDirectedActions(actor);
        }
    }

    private static boolean recovering(Mob mob) {
        Long recoveryEnd = MISS_RECOVERY_END.get(mob.getUUID());
        if (recoveryEnd == null) {
            return false;
        }
        if (mob.level().getGameTime() < recoveryEnd) {
            return true;
        }
        MISS_RECOVERY_END.remove(mob.getUUID(), recoveryEnd);
        TechniqueLeaps.unlockDirectedActions(mob);
        return false;
    }

    private static boolean directs(Mob mob) {
        return mob instanceof Zombie && UndeadFabric.accepts(mob) && tier(mob) != HumanoidTier.MORTAL;
    }

    private static HumanoidTier tier(Mob mob) {
        // 用已绑定的本地快照校验版本，首次建身仍交给健康宿主。
        BodySnapshot body = VanillaDamageFabric.existingMobBody(mob)
                .or(() -> VanillaDamageFabric.inspectBody(mob))
                .orElse(null);
        if (body == null) {
            mob.removeAttached(TIER);
            return HumanoidTier.MORTAL;
        }
        var cached = mob.getAttached(TIER);
        String bodyId = body.getId();
        long revision = body.getRevision();
        if (cached != null && cached.revision() == revision && cached.bodyId().equals(bodyId)) {
            return cached.tier();
        }
        HumanoidTier tier = HumanoidTier.of(body);
        mob.setAttached(TIER, new TierAtRevision(bodyId, revision, tier));
        return tier;
    }

    private static LivingEntity findEntity(UUID entityId) {
        MinecraftServer server = currentServer;
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private static double horizontalDistance(LivingEntity first, LivingEntity second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return Math.sqrt(x * x + z * z);
    }

    private static boolean leapSunlit(LivingEntity entity) {
        if (entity.level().isClientSide()) {
            return false;
        }
        boolean monstersBurn = entity.level().environmentAttributes()
                .getValue(EnvironmentAttributes.MONSTERS_BURN, entity.position());
        if (!monstersBurn || entity.getLightLevelDependentMagicValue() <= MIN_DIRECT_SUNLIGHT_BRIGHTNESS) {
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

    private static boolean sunlightBlocksLeap(LivingEntity entity) {
        return entity.getType() != EntityTypes.HUSK && leapSunlit(entity);
    }
}
