package dev.kizuna.meridian.undead.fabric;

import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.technique.fabric.TechniqueFabric;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.server.TechniqueState;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.GameType;

import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.SPAWN;
import static dev.kizuna.meridian.undead.fabric.CorpseTestBodies.body;

public final class CorpseScrollGameTest {
    private static final Identifier SCROLL = Identifier.parse("meridian_undead:corpse_movement_scroll");
    private static final Identifier READ = Identifier.parse("meridian_undead_test:read_corpse_scroll");

    @GameTest
    public void corpseScrollTooltipEndsWithInnateHint(GameTestHelper test) {
        // 从正式物品取悬停末行，区分天生卷和学成即耗的普通卷。
        var item = BuiltInRegistries.ITEM.getValue(SCROLL);
        var lines = new ArrayList<Component>();
        item.appendHoverText(new ItemStack(item), Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT,
                lines::add, TooltipFlag.NORMAL);
        var last = lines.getLast();
        test.assertTrue(last.getContents() instanceof TranslatableContents translation
                && translation.getKey().equals("tooltip.meridian_technique.scroll.innate"), "天生卷末行错误 " + last);
        test.succeed();
    }

    @GameTest
    public void humanBodyRejectsCorpseScroll(GameTestHelper test) {
        // 村民使用人体模板。
        var living = test.spawn(EntityTypes.VILLAGER, SPAWN, SPAWN, SPAWN);
        assertRejected(test, living, "这具身体用不了这一式");
    }

    @GameTest
    public void lockedCorpseScrollExplainsOpeningRequirement(GameTestHelper test) {
        // 白僵的躯干和四肢经脉尚未开拓。
        var white = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.MORTAL);
        assertRejected(test, white, "这具身体尚未达到此式的境界或开脉要求");
    }

    @GameTest
    public void unlockedCorpseAlreadyKnowsWholeScroll(GameTestHelper test) {
        // 黑僵天生已会纵和铜皮，无需学习记录。
        var black = CorpseBirthGameTest.commanded(test, EntityTypes.HUSK, HumanoidTier.WANDERING_QI);
        assertRejected(test, black, "你已经学会这一卷的全部功法");
    }

    private static void assertRejected(GameTestHelper test, LivingEntity host, String expected) {
        // 离线测试玩家借已生成宿主的 UUID 右键。正式功法服务仍从该实体读取当前身体。
        var level = test.getLevel();
        var server = level.getServer();
        var profile = new GameProfile(host.getUUID(), "scroll_reader");
        var cookie = CommonListenerCookie.createInitial(profile, false /* transferred */);
        var reader = new ServerPlayer(server, level, profile, cookie.clientInformation());
        var connection = new RecordingConnection(List.of());
        new ServerGamePacketListenerImpl(server, connection, reader, cookie);
        reader.setGameMode(GameType.SURVIVAL);
        var hand = InteractionHand.MAIN_HAND;
        var stack = new ItemStack(BuiltInRegistries.ITEM.getValue(SCROLL));
        reader.setItemInHand(hand, stack);
        // 首次认领会落实村民出生时的功法，先让宿主完成出生授式，再取读卷前的学习记录。
        var knowledge = TechniqueFabric.service().known(host.getUUID());
        test.assertFalse(knowledge.isEmpty(), "类人宿主未列出天生功法");
        var before = TechniqueState.read(BodyProtobuf.fromMessage(body(host))).getKnownList();
        var advancement = server.getAdvancements().get(READ);
        var progress = reader.getAdvancements().getOrStartProgress(advancement);
        try {
            var result = reader.gameMode.useItem(reader, level, stack, hand);
            test.assertTrue(result == InteractionResult.FAIL, "读卷没有拒绝");
            var messages = connection.packets().stream()
                    .filter(ClientboundSystemChatPacket.class::isInstance)
                    .map(ClientboundSystemChatPacket.class::cast)
                    .filter(ClientboundSystemChatPacket::overlay)
                    .map(packet -> packet.content().getString())
                    .toList();
            test.assertTrue(messages.equals(List.of(expected)), "读卷提示错误 " + messages);
            test.assertTrue(reader.getItemInHand(hand) == stack && stack.getCount() == 1, "拒绝后卷轴改变");
            var after = TechniqueState.read(BodyProtobuf.fromMessage(body(host))).getKnownList();
            test.assertTrue(before.equals(after), "读卷改写了学习记录");
            test.assertFalse(progress.isDone(), "拒绝读卷触发了学卷进度");
        } finally {
            connection.disconnectFromClient();
        }
        test.succeed();
    }
}
