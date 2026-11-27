package dev.kizuna.meridian.undead.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kizuna.meridian.undead.server.NightVisitRules;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/** 由持货者的剧情宿主发签。区块未加载时保留在场记录，原实体回来才继续。 */
public final class NightVisits extends SavedData {
    public enum Status { WAITING, PRESENT, SUSPENDED, WON, ESCAPED, LOST }

    private record Visit(UUID ticket, UUID owner, GlobalPos feet, Status status) {
        static final Codec<Visit> CODEC = RecordCodecBuilder.create(builder -> builder.group(
                UUIDUtil.CODEC.fieldOf("ticket").forGetter(Visit::ticket),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Visit::owner),
                GlobalPos.CODEC.fieldOf("feet").forGetter(Visit::feet),
                Codec.STRING.xmap(Status::valueOf, Status::name).fieldOf("status").forGetter(Visit::status)
        ).apply(builder, Visit::new));
    }

    private record Claim(UUID ticket, UUID owner) {
        static final Codec<Claim> CODEC = RecordCodecBuilder.create(builder -> builder.group(
                UUIDUtil.CODEC.fieldOf("ticket").forGetter(Claim::ticket),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Claim::owner)
        ).apply(builder, Claim::new));
    }

    private static final AttachmentType<Claim> CLAIM = AttachmentRegistry.create(
            Identifier.parse("meridian_undead:night_visit"), builder -> builder.persistent(Claim.CODEC));
    private static final String TAG = "meridian_undead.night_visit";
    private static final int MAX_ARRIVAL_DISTANCE = 24;
    private static final SavedDataType<NightVisits> TYPE = new SavedDataType<>(
            Identifier.parse("meridian_undead:night_visits"), () -> new NightVisits(List.of()),
            Visit.CODEC.listOf().xmap(NightVisits::new, ledger -> List.copyOf(ledger.visits.values())), null);
    private final Map<UUID, Visit> visits = new HashMap<>();
    private final Set<UUID> active = new HashSet<>();

    private NightVisits(List<Visit> saved) {
        saved.forEach(this::index);
    }

    private void index(Visit visit) {
        visits.put(visit.ticket(), visit);
        if (visit.status() == Status.PRESENT) {
            active.add(visit.ticket());
        } else {
            active.remove(visit.ticket());
        }
    }

    private void store(Visit visit) {
        index(visit);
        setDirty();
    }

    private static NightVisits ledger(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static Status status(ServerPlayer player, UUID ticket) {
        var server = player.level().getServer();
        var visit = ledger(server).visits.get(ticket);
        if (visit == null || !visit.owner().equals(player.getUUID())) {
            return Status.WAITING;
        }
        if (visit.status() == Status.PRESENT && actor(server, visit) == null) {
            return Status.SUSPENDED;
        }
        return visit.status();
    }

    /** 只在夜行资格和签物由调用方核准时调用；重试同一签不会再招一只。 */
    public static Status request(ServerPlayer player, UUID ticket, BlockPos feet) {
        var level = player.level();
        var ledger = ledger(level.getServer());
        if (ledger.visits.containsKey(ticket)) {
            return status(player, ticket);
        }
        UUID owner = player.getUUID();
        if (!player.isAlive() || player.isCreative() || player.isSpectator() || level.dimension() != Level.OVERWORLD
                || !NightVisitRules.night(level.getOverworldClockTime())
                || !player.blockPosition().closerThan(feet, MAX_ARRIVAL_DISTANCE)
                || ledger.active.stream().map(ledger.visits::get).anyMatch(visit -> visit.owner().equals(owner))) {
            return Status.WAITING;
        }
        if (!level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)
                || !level.getBlockState(feet.below()).isSolid() || !level.getFluidState(feet).isEmpty()
                || !level.getFluidState(feet.above()).isEmpty()) {
            return Status.WAITING;
        }
        var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT);
        if (zombie == null || !UndeadFabric.accepts(zombie)) {
            return Status.WAITING;
        }
        zombie.setUUID(NightVisitRules.visitor(ticket));
        zombie.setPos(Vec3.atBottomCenterOf(feet));
        zombie.setPersistenceRequired();
        zombie.addTag(TAG);
        zombie.setAttached(CLAIM, new Claim(ticket, owner));
        if (!level.noCollision(zombie) || !level.addFreshEntity(zombie)) {
            return Status.WAITING;
        }
        zombie.setTarget(player);
        ledger.bind(zombie, level);
        return Status.PRESENT;
    }

    private static Zombie actor(MinecraftServer server, Visit visit) {
        var level = server.getLevel(visit.feet().dimension());
        if (level != null && level.getEntity(NightVisitRules.visitor(visit.ticket())) instanceof Zombie zombie
                && zombie.entityTags().contains(TAG)) {
            return zombie;
        }
        return null;
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var saved = server.overworld().getDataStorage().get(TYPE);
            if (saved != null) {
                for (var ticket : Set.copyOf(saved.active)) {
                    saved.follow(server, saved.visits.get(ticket));
                }
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity.level() instanceof ServerLevel level) {
                var saved = level.getServer().overworld().getDataStorage().get(TYPE);
                if (saved != null) {
                    saved.died(entity, level, source.getEntity());
                }
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Zombie zombie && zombie.entityTags().contains(TAG)) {
                ledger(level.getServer()).bind(zombie, level);
            }
        });
    }

    private void bind(Zombie zombie, ServerLevel level) {
        var claim = zombie.getAttached(CLAIM);
        if (claim == null || !zombie.getUUID().equals(NightVisitRules.visitor(claim.ticket()))) {
            return;
        }
        var previous = visits.get(claim.ticket());
        if (previous != null && (previous.status() != Status.PRESENT || !previous.owner().equals(claim.owner()))) {
            zombie.discard();
            return;
        }
        var feet = GlobalPos.of(level.dimension(), zombie.blockPosition());
        var bound = new Visit(claim.ticket(), claim.owner(), feet, Status.PRESENT);
        if (!bound.equals(previous)) {
            store(bound);
        }
    }

    private void follow(MinecraftServer server, Visit visit) {
        var zombie = actor(server, visit);
        if (zombie == null) {
            return;
        }
        var feet = GlobalPos.of(zombie.level().dimension(), zombie.blockPosition());
        var target = server.getPlayerList().getPlayer(visit.owner());
        if (target == null) {
            zombie.setTarget(null);
            zombie.setNoAi(true /* noAi */);
        } else if (target.isAlive()) {
            zombie.setNoAi(false /* noAi */);
            if (target.level() != zombie.level() || !target.blockPosition()
                    .closerThan(feet.pos(), NightVisitRules.ESCAPE_DISTANCE)) {
                store(new Visit(visit.ticket(), visit.owner(), feet, Status.ESCAPED));
                zombie.discard();
                return;
            }
            if (zombie.getTarget() != target) {
                zombie.setTarget(target);
            }
        }
        if (!feet.equals(visit.feet())) {
            store(new Visit(visit.ticket(), visit.owner(), feet, Status.PRESENT));
        }
    }

    private void died(LivingEntity entity, ServerLevel level, Entity killer) {
        if (entity instanceof ServerPlayer player) {
            for (var ticket : Set.copyOf(active)) {
                var visit = visits.get(ticket);
                var zombie = actor(level.getServer(), visit);
                if (visit.owner().equals(player.getUUID()) && zombie != null && killer == zombie) {
                    store(new Visit(ticket, visit.owner(), visit.feet(), Status.LOST));
                    zombie.discard();
                }
            }
        } else if (entity.hasAttached(CLAIM)) {
            var ticket = entity.getAttached(CLAIM).ticket();
            var visit = visits.get(ticket);
            if (visit != null && visit.status() == Status.PRESENT) {
                store(new Visit(ticket, visit.owner(), visit.feet(), Status.WON));
            }
        }
    }
}
