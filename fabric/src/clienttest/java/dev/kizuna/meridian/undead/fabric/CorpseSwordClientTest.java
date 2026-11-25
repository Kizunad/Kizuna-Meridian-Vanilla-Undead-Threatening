package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidEntityAccess;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.server.TechniqueService;
import dev.kizuna.meridian.undead.server.UndeadContent;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.ArrayList;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.Zombie.ZombieGroupData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

public final class CorpseSwordClientTest implements FabricClientGameTest {
    private static final String WIND = "meridian_piglin_sword:wind_around";
    private static final int NATURAL_DRAWS = 8192;
    private static final int FRAMES = 40;
    private static final int START_Y_OFFSET = 4;
    private static final int CAMERA_DISTANCE = 4;
    private static final int SHIELD_DISTANCE = 2;
    private static final int BLACK_QI = 10;
    private static final int WIND_PRICE = 3;
    private static final float FACE_TARGET = -90;
    private static final float FACE_CASTER = 90;
    private static final int CAMERA_PITCH = 5;
    private static final String FILE = "black-corpse-wind";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("weather clear");
            world.getServer().runCommand("time set noon");
            var scene = world.getServer().computeOnServer(server -> prepare(server.overworld()));
            context.waitFor(client -> client.level.getEntity(scene.casterNetworkId()) != null);
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                client.gui.setScreen(null);
                if (!client.gui.hud.isHidden()) {
                    client.gui.hud.toggle();
                }
            });
            film(context, world, scene);
        }
    }

    private static Scene prepare(ServerLevel level) {
        // 观察者看向两个实体中点，场地抬过地表，避免附近方块遮住金剑和盾。
        var player = level.getServer().getPlayerList().getPlayers().getFirst();
        var column = player.blockPosition();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        int y = surface + START_Y_OFFSET;
        var start = new BlockPos(column.getX(), y, column.getZ());
        var caster = naturalBlackCorpse(level, start);
        var defender = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
        defender.setNoGravity(true /* noGravity */);
        defender.setNoAi(true /* noAi */);
        defender.snapTo(start.getX() + SHIELD_DISTANCE, y, start.getZ(), FACE_CASTER, 0);
        defender.setYHeadRot(FACE_CASTER);
        defender.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        level.addFreshEntity(defender);
        defender.startUsingItem(InteractionHand.OFF_HAND);
        var command = level.getServer().createCommandSourceStack().withLevel(level);
        level.getServer().getCommands().performPrefixedCommand(command, "tp @a " + (start.getX() + 1) + " "
                + y + " " + (start.getZ() - CAMERA_DISTANCE) + " 0 " + CAMERA_PITCH);
        return new Scene(caster.getUUID(), caster.getId(), defender.getUUID());
    }

    private static Zombie naturalBlackCorpse(ServerLevel level, BlockPos start) {
        // 只将自然抽到游炁、且原版确实配了金剑的成年实体加入世界。
        var difficulty = level.getCurrentDifficultyAt(start);
        var adult = new ZombieGroupData(false /* isBaby */, false /* canSpawnJockey */);
        for (int draw = 0; draw < NATURAL_DRAWS; draw++) {
            var corpse = EntityTypes.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.NATURAL);
            corpse.snapTo(start.getX(), start.getY(), start.getZ(), FACE_TARGET, 0);
            corpse.finalizeSpawn(level, difficulty, EntitySpawnReason.NATURAL, adult);
            var plan = ((HumanoidEntityAccess) corpse).meridian$birthPlan();
            if (plan.selected().orElseThrow() == HumanoidTier.WANDERING_QI
                    && corpse.getMainHandItem().is(Items.GOLDEN_SWORD)) {
                corpse.setYHeadRot(FACE_TARGET);
                corpse.setNoGravity(true /* noGravity */);
                corpse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
                level.addFreshEntity(corpse);
                return corpse;
            }
        }
        throw new AssertionError("没有自然抽到持金剑的黑僵");
    }

    private static void film(ClientGameTestContext context, TestSingleplayerContext world, Scene scene) {
        var rows = new ArrayList<String>();
        rows.add("frame\tserver_tick\tpearl_qi\tblocking\ttechnique\ttarget");
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            var caster = (Zombie) level.getEntity(scene.caster());
            var defender = (Vindicator) level.getEntity(scene.defender());
            boolean knows = TechniqueFabric.service().known(scene.caster()).stream()
                    .anyMatch(entry -> entry.technique().getId().equals(WIND));
            if (!knows || sample(level, scene).balance() != BLACK_QI) {
                throw new AssertionError("自然黑僵未学绕或出生尸丹余额错误");
            }
            caster.setTarget(defender);
        });
        boolean paid = false;
        for (int frame = 0; frame < FRAMES; frame++) {
            context.waitTick();
            var state = world.getServer().computeOnServer(server -> sample(server.overworld(), scene));
            rows.add(frame + "\t" + state.tick() + "\t" + state.balance() + "\t" + state.blocking()
                    + "\t" + state.form() + "\t" + state.target());
            paid |= state.form().equals(WIND) && state.target().equals(scene.defender().toString())
                    && state.blocking() && state.balance() == BLACK_QI - WIND_PRICE;
            UndeadLeapClientTest.captureFrame(context, FILE, frame);
        }
        UndeadLeapClientTest.write(FILE + ".tsv", String.join("\n", rows) + "\n");
        if (!paid) {
            throw new AssertionError("黑僵未以三元尸丹对举盾目标起绕");
        }
    }

    private static Frame sample(ServerLevel level, Scene scene) {
        var caster = (Zombie) level.getEntity(scene.caster());
        var defender = (Vindicator) level.getEntity(scene.defender());
        var body = VanillaDamageFabric.inspectBody(caster).orElseThrow();
        var pearl = body.getOrganStatesList().stream().filter(organ -> organ.getId().equals(UndeadContent.PEARL))
                .findFirst().orElseThrow();
        double balance = BodyProtobuf.fromInventory(pearl.getBuffer()).totalQi();
        var cast = TechniqueFabric.service().observe(scene.caster()).flatMap(TechniqueService.Observation::cast);
        var request = cast.map(forecast -> forecast.active().getRequest());
        String form = request.map(value -> value.getTechnique().getId()).orElse("");
        String target = request.map(value -> value.getTargetId()).orElse("");
        return new Frame(level.getGameTime(), balance, defender.isBlocking(), form, target);
    }

    private record Scene(UUID caster, int casterNetworkId, UUID defender) { }

    private record Frame(long tick, double balance, boolean blocking, String form, String target) { }
}
