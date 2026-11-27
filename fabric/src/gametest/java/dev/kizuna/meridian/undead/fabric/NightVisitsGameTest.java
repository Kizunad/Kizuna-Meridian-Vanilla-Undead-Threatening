package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.undead.server.NightVisitRules;
import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class NightVisitsGameTest {
    private static final int MIDNIGHT = 18_000;

    private record Walker(ServerPlayer player, RecordingConnection connection) implements AutoCloseable {
        static Walker login(GameTestHelper test) {
            var level = test.getLevel();
            var id = UUID.randomUUID();
            var profile = new GameProfile(id, "Walker" + id.toString().substring(0, 8));
            var cookie = CommonListenerCookie.createInitial(profile, false /* transferred */);
            var player = new ServerPlayer(level.getServer(), level, profile, cookie.clientInformation());
            var connection = new RecordingConnection(List.of());
            TestPlayers.logIn(player, connection);
            player.setGameMode(GameType.SURVIVAL);
            return new Walker(player, connection);
        }

        @Override
        public void close() {
            connection.disconnectFromClient();
        }
    }

    @GameTest
    public void unloadedNightVisitorRebindsOnceAndRecordsVictory(GameTestHelper test) {
        long before = test.getLevel().getOverworldClockTime();
        try (var walker = Walker.login(test)) {
            encounter(test, walker.player());
        } finally {
            test.setTime(before);
        }
        test.succeed();
    }

    @GameTest
    public void departingOwnerEscapesWithoutASecondVisit(GameTestHelper test) {
        var walker = Walker.login(test);
        var player = walker.player();
        var ticket = UUID.randomUUID();
        var feet = feet(test, player);
        begin(test, player, ticket, feet);
        player.setPos(Vec3.atBottomCenterOf(feet.east(NightVisitRules.ESCAPE_DISTANCE + 1)));
        test.succeedWhen(() -> {
            test.assertTrue(NightVisits.request(player, ticket, feet) == NightVisits.Status.ESCAPED,
                    "持货者走远后原签仍在追击");
            walker.close();
        });
    }

    @GameTest
    public void visitorsKillingBlowRecordsOwnersDefeat(GameTestHelper test) {
        try (var walker = Walker.login(test)) {
            var player = walker.player();
            var ticket = UUID.randomUUID();
            var feet = feet(test, player);
            var zombie = begin(test, player, ticket, feet);
            player.die(player.damageSources().mobAttack(zombie));
            test.assertTrue(NightVisits.status(player, ticket) == NightVisits.Status.LOST, "亡灵击败本人未结原签");
        }
        test.succeed();
    }

    private static BlockPos feet(GameTestHelper test, ServerPlayer player) {
        var feet = test.absolutePos(new BlockPos(1, 1, 1));
        test.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        player.setPos(Vec3.atBottomCenterOf(feet.east(1)));
        return feet;
    }

    private static Zombie begin(GameTestHelper test, ServerPlayer player, UUID ticket,
                                BlockPos feet) {
        var level = test.getLevel();
        long before = level.getOverworldClockTime();
        try {
            test.setTime(MIDNIGHT);
            test.assertTrue(NightVisits.request(player, ticket, feet) == NightVisits.Status.PRESENT, "夜访未到场");
            var zombie = (Zombie) level.getEntity(NightVisitRules.visitor(ticket));
            test.assertTrue(zombie.getTarget() == player, "亡灵没有追持货者");
            return zombie;
        } finally {
            test.setTime(before);
        }
    }

    private static void encounter(GameTestHelper test, ServerPlayer player) {
        var level = test.getLevel();
        var feet = feet(test, player);
        var ticket = UUID.randomUUID();
        test.setTime(0);
        test.assertTrue(NightVisits.request(player, ticket, feet) == NightVisits.Status.WAITING, "白日招来了亡灵");
        var zombie = begin(test, player, ticket, feet);
        var id = NightVisitRules.visitor(ticket);
        var saved = CorpseTestBodies.save(zombie);
        zombie.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        test.assertTrue(NightVisits.request(player, ticket, feet) == NightVisits.Status.SUSPENDED, "卸载被当作胜逃败");
        var loaded = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.LOAD);
        CorpseTestBodies.load(loaded, saved);
        test.assertTrue(level.addFreshEntity(loaded), "未能重载原尸身");
        test.assertTrue(level.getEntity(id) == loaded && NightVisits.request(player, ticket, feet)
                == NightVisits.Status.PRESENT, "未重绑原实体");
        loaded.kill(level);
        test.assertTrue(NightVisits.request(player, ticket, feet) == NightVisits.Status.WON, "原签未结或又招了一只");
    }
}
