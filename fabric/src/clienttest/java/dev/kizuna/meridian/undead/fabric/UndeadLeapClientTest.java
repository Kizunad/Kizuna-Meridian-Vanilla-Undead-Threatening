package dev.kizuna.meridian.undead.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastOutcome;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.CastRequest;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueFailure;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueReference;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 黑僵定向纵的固定侧面逐 tick 连拍，产物用于复核命中和扑空恢复。 */
public final class UndeadLeapClientTest implements FabricClientGameTest {
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final int ARENA_RADIUS = 12;
    private static final int ARENA_HEIGHT = 8;
    private static final int HIT_FRAMES = 34;
    private static final int MISS_FRAMES = 56;
    private static final int SETTLE_TICKS = 20;
    private static final int MISS_MOVE_FRAME = 14;
    private static final int MISS_RECOVERY_WALK_FRAME = 44;
    private static final int GROUND_Y_OFFSET = 0;
    private static final double ACTOR_X_OFFSET = -5.5;
    private static final double TARGET_X_OFFSET = 0.5;
    private static final double ACTOR_Z_OFFSET = 0.5;
    private static final double MISS_TARGET_SIDE_STEP = 1.0;
    private static final double RECOVERY_TARGET_WALK_STEP = 0.15;
    private static final double CAMERA_X_OFFSET = -2.5;
    private static final double CAMERA_Z_OFFSET = -12.0;
    private static final double CAMERA_EYE_HEIGHT = 2.8;
    private static final float CAMERA_YAW = 0.0f;
    private static final float CAMERA_PITCH = 8.0f;
    private static final String LEAP_ID = "meridian_undead:leap";
    private static final String HIT_RECORD = "undead-leap-hit.tsv";
    private static final String MISS_RECORD = "undead-leap-miss.tsv";
    private static final String HIT_FRAME_PREFIX = "undead_leap_hit";
    private static final String MISS_FRAME_PREFIX = "undead_leap_miss";
    private static final Path SCREENSHOT_DIRECTORY = Path.of("screenshots");
    private static final Film HIT_FILM = new Film(HIT_FRAMES, HIT_FRAME_PREFIX, FilmKind.HIT);
    private static final Film MISS_FILM = new Film(MISS_FRAMES, MISS_FRAME_PREFIX, FilmKind.MISS);

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Arena hitArena = prepare(context, world);
            context.waitFor(client -> client.level.getEntity(hitArena.cameraEntityId()) != null);
            configureCamera(context, hitArena.cameraEntityId());
            context.waitTicks(SETTLE_TICKS);
            List<String> hitRows = film(context, world, hitArena, HIT_FILM);
            write(HIT_RECORD, header() + String.join("\n", hitRows) + "\n");

            Arena missArena = resetArena(world, hitArena.origin());
            context.waitFor(client -> client.level.getEntity(missArena.cameraEntityId()) != null);
            configureCamera(context, missArena.cameraEntityId());
            context.waitTicks(SETTLE_TICKS);
            List<String> missRows = film(context, world, missArena, MISS_FILM);
            write(MISS_RECORD, header() + String.join("\n", missRows) + "\n");
            context.waitTicks(SETTLE_TICKS);
        }
    }

    private static Arena prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        int surface = world.getServer().computeOnServer(server -> server.overworld().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0));
        BlockPos origin = new BlockPos(0, surface, 0);
        command(world, "time set noon");
        command(world, "weather clear");
        command(world, "gamerule advance_time false");
        command(world, "gamemode spectator @a");
        clearArena(world, origin);
        Arena arena = world.getServer().computeOnServer(server -> spawnArena(server, origin));
        context.runOnClient(client -> {
            client.gui.setScreen(null);
            if (!client.gui.hud.isHidden()) {
                client.gui.hud.toggle();
            }
        });
        return arena;
    }

    private static Arena resetArena(TestSingleplayerContext world, BlockPos origin) {
        clearArena(world, origin);
        return world.getServer().computeOnServer(server -> spawnArena(server, origin));
    }

    private static void clearArena(TestSingleplayerContext world, BlockPos origin) {
        BlockPos from = origin.offset(-ARENA_RADIUS, 0, -ARENA_RADIUS);
        BlockPos to = origin.offset(ARENA_RADIUS, ARENA_HEIGHT, ARENA_RADIUS);
        command(world, "fill " + coordinates(from) + " " + coordinates(to) + " air");
        command(world, "fill " + coordinates(from.below()) + " "
                + coordinates(to.atY(origin.getY() - 1)) + " stone");
        AABB area = new AABB(Vec3.atLowerCornerOf(from), Vec3.atLowerCornerOf(to.offset(1, 1, 1)));
        world.getServer().runOnServer(server -> server.overworld()
                .getEntities((Entity) null, area, entity -> !(entity instanceof Player))
                .forEach(Entity::discard));
    }

    private static Arena spawnArena(MinecraftServer server, BlockPos origin) {
        ServerLevel level = server.overworld();
        double groundY = origin.getY() + GROUND_Y_OFFSET;
        Vec3 actorPosition = new Vec3(origin.getX() + ACTOR_X_OFFSET, groundY,
                origin.getZ() + ACTOR_Z_OFFSET);
        Vec3 targetPosition = new Vec3(origin.getX() + TARGET_X_OFFSET, groundY,
                origin.getZ() + ACTOR_Z_OFFSET);
        Zombie actor = spawnCorpse(server, level, actorPosition);
        Mob target = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
        if (target == null) {
            throw new IllegalStateException("无法生成录像目标");
        }
        target.setPos(targetPosition);
        level.addFreshEntity(target);
        target.setNoAi(true /* noAi */);
        actor.setYRot(-90);
        actor.setYHeadRot(-90);
        actor.setOnGround(true /* onGround */);
        actor.setNoAi(true /* noAi */);
        actor.setTarget(target);
        ArmorStand camera = spawnCamera(level, origin, groundY);
        return new Arena(actor.getUUID(), target.getUUID(), camera.getId(), origin);
    }

    private static ArmorStand spawnCamera(ServerLevel level, BlockPos origin, double groundY) {
        double cameraX = origin.getX() + CAMERA_X_OFFSET;
        double cameraY = groundY + CAMERA_EYE_HEIGHT;
        double cameraZ = origin.getZ() + CAMERA_Z_OFFSET;
        ArmorStand camera = new ArmorStand(level, cameraX, cameraY, cameraZ);
        camera.setInvisible(true /* invisible */);
        camera.setNoGravity(true /* noGravity */);
        camera.setYRot(CAMERA_YAW);
        camera.setXRot(CAMERA_PITCH);
        level.addFreshEntity(camera);
        return camera;
    }

    public static Zombie spawnCorpse(MinecraftServer server, ServerLevel level, Vec3 position) {
        Feedback feedback = new Feedback();
        var source = server.createCommandSourceStack()
                .withLevel(level)
                .withPosition(position)
                .withSource(feedback);
        String command = "meridian humanoid spawn husk " + HumanoidTier.WANDERING_QI.id();
        server.getCommands().performPrefixedCommand(source, command);
        if (feedback.message.isBlank()) {
            throw new IllegalStateException("生成黑僵没有命令回执");
        }
        UUID entityId = UUID.fromString(feedback.message.substring(feedback.message.lastIndexOf('：') + 1));
        Entity entity = level.getEntity(entityId);
        if (!(entity instanceof Zombie zombie)) {
            throw new IllegalStateException("生成的录像主体不是僵尸：" + feedback.message);
        }
        zombie.setPos(position);
        return zombie;
    }

    private static void configureCamera(ClientGameTestContext context, int cameraEntityId) {
        context.runOnClient(client -> {
            client.options.setCameraType(CameraType.FIRST_PERSON);
            Entity cameraEntity = client.level.getEntity(cameraEntityId);
            if (cameraEntity == null) {
                throw new AssertionError("客户端没有收到固定侧面机位");
            }
            client.setCameraEntity(cameraEntity);
        });
    }

    private static List<String> film(ClientGameTestContext context, TestSingleplayerContext world, Arena arena,
                                     Film film) {
        String cast = world.getServer().computeOnServer(server -> cast(server, arena.actorId(), arena.targetId()));
        FilmKind kind = film.kind();
        List<String> rows = new ArrayList<>();
        for (int frame = 1; frame <= film.frameCount(); frame++) {
            context.waitTicks(1);
            if (kind == FilmKind.MISS && frame == MISS_MOVE_FRAME) {
                world.getServer().runOnServer(server -> moveTargetForMiss(server, arena));
            }
            if (kind == FilmKind.MISS && frame >= MISS_RECOVERY_WALK_FRAME) {
                world.getServer().runOnServer(server -> walkTargetAfterRecovery(server, arena));
            }
            String state = world.getServer().computeOnServer(server -> state(server, arena));
            captureFrame(context, film.framePrefix(), frame);
            rows.add(frame + "\t" + state + "\t" + cast);
        }
        return rows;
    }

    private static String cast(MinecraftServer server, UUID actorId, UUID targetId) {
        Entity entity = server.overworld().getEntity(actorId);
        Entity targetEntity = server.overworld().getEntity(targetId);
        if (!(entity instanceof Zombie actor) || !(targetEntity instanceof LivingEntity)) {
            throw new IllegalStateException("施放时找不到录像实体");
        }
        var body = VanillaDamageFabric.inspectBody(actor).orElseThrow();
        var definition = TechniqueFabric.service().registry().findLatest(LEAP_ID).orElseThrow();
        TechniqueReference reference = TechniqueReference.newBuilder()
                .setId(definition.getId())
                .setVersion(definition.getVersion())
                .build();
        CastRequest request = CastRequest.newBuilder()
                .setBodyId(body.getId())
                .setTechnique(reference)
                .setCastId("client_leap_" + UUID.randomUUID())
                .build();
        CastOutcome outcome = TechniqueFabric.service().cast(actor.getUUID(), request);
        if (outcome.getFailure() != TechniqueFailure.TECHNIQUE_FAILURE_UNSPECIFIED) {
            throw new IllegalStateException("录像纵施放失败：" + outcome);
        }
        actor.setNoAi(false /* noAi */);
        actor.getNavigation().stop();
        return outcome.getPhase().name();
    }

    private static void moveTargetForMiss(MinecraftServer server, Arena arena) {
        Entity targetEntity = server.overworld().getEntity(arena.targetId());
        if (!(targetEntity instanceof LivingEntity target)) {
            throw new IllegalStateException("扑空段找不到录像实体");
        }
        double targetZ = target.getZ() + MISS_TARGET_SIDE_STEP;
        target.setPos(target.getX(), target.getY(), targetZ);
    }

    private static void walkTargetAfterRecovery(MinecraftServer server, Arena arena) {
        Entity targetEntity = server.overworld().getEntity(arena.targetId());
        if (!(targetEntity instanceof LivingEntity target)) {
            throw new IllegalStateException("恢复走动段找不到录像实体");
        }
        target.setPos(target.getX(), target.getY(), target.getZ() + RECOVERY_TARGET_WALK_STEP);
    }

    private static String state(MinecraftServer server, Arena arena) {
        Entity actorEntity = server.overworld().getEntity(arena.actorId());
        Entity targetEntity = server.overworld().getEntity(arena.targetId());
        if (!(actorEntity instanceof Mob actor) || !(targetEntity instanceof Mob target)) {
            throw new IllegalStateException("录像实体提前消失");
        }
        return String.format(Locale.ROOT, "%d\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.2f\t%b\t%b",
                server.overworld().getGameTime(), actor.getX(), actor.getY(), actor.getZ(), target.getX(),
                target.getZ(), target.getHealth(), actor.isNoAi(), target.isNoAi());
    }

    private static void captureFrame(ClientGameTestContext context, String prefix, int frame) {
        String fileName = String.format(Locale.ROOT, "%s_%03d.png", prefix, frame);
        Path file = SCREENSHOT_DIRECTORY.resolve(fileName);
        context.runOnClient(client -> Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                image -> saveCapture(image, file)));
    }

    private static void saveCapture(NativeImage image, Path relativeFile) {
        Path file = FabricLoader.getInstance().getGameDir().resolve(relativeFile);
        try (image) {
            Files.createDirectories(file.getParent());
            image.writeToFile(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String header() {
        return "帧\t服务端 tick\t黑僵 x\t黑僵 y\t黑僵 z\t目标 x\t目标 z\t目标血量\t"
                + "黑僵 noAi\t目标 noAi\t纵阶段\n";
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }

    private static void command(TestSingleplayerContext world, String command) {
        world.getServer().runCommand(command);
    }

    private static void write(String name, String content) {
        try {
            Files.writeString(FabricLoader.getInstance().getGameDir().resolve(name), content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Arena(UUID actorId, UUID targetId, int cameraEntityId, BlockPos origin) { }

    private record Film(int frameCount, String framePrefix, FilmKind kind) { }

    private enum FilmKind { HIT, MISS }

    private static final class Feedback implements CommandSource {
        private String message = "";

        @Override
        public void sendSystemMessage(Component text) {
            message = text.getString();
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
