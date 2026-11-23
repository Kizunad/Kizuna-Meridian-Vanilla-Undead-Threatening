package dev.kizuna.meridian.ui.client;

import dev.kizuna.meridian.combat.fabric.CombatChannels;
import dev.kizuna.meridian.combat.fabric.CombatFabric;
import dev.kizuna.meridian.technique.fabric.TechniqueCodex;
import dev.kizuna.meridian.technique.fabric.TechniqueScrollItems;
import dev.kizuna.meridian.undead.fabric.UndeadLeapClientTest;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec2;

public final class CorpseScrollClientTest implements FabricClientGameTest {
    private static final String BOOK = "meridian_undead:corpse_arts";
    private static final String VOLUME = "meridian_undead:corpse_movement";
    private static final Identifier SCROLL = Identifier.parse(VOLUME + "_scroll");
    private static final int GUI_SCALE = 2;
    private static final int SLOT_CENTER = 8;
    private static final int TAB_SLOTS = 45;

    @Override
    public void runTest(ClientGameTestContext context) {
        // 先用普通玩家知识翻书，再把黑僵的实际服务端知识投影送到观察端。
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("give @a " + SCROLL);
            var item = TechniqueScrollItems.scrolls().get(SCROLL);
            context.waitFor(client -> client.player.getAbilities().instabuild
                    && client.player.getInventory().contains(stack -> stack.is(item)));
            var cursor = context.computeOnClient(CorpseScrollClientTest::openTab);
            var input = context.getInput();
            input.setCursorPos(0, 0);
            screenshot(context, "corpse-scroll-creative");
            input.setCursorPos(cursor.x, cursor.y);
            screenshot(context, "corpse-scroll-item");
            input.setCursorPos(0, 0);
            context.runOnClient(CorpseScrollClientTest::openBook);
            context.waitFor(client -> state() == CodexProgress.State.MISSING);
            screenshot(context, "corpse-scroll-unknown");
            server.runOnServer(CorpseScrollClientTest::sendCorpseKnowledge);
            context.waitFor(client -> state() == CodexProgress.State.LEARNED);
            screenshot(context, "corpse-scroll-known");
        }
    }

    private static Vec2 openTab(Minecraft client) {
        // 打开正式功法创造页并定位卷轴格，截图使用真实悬停提示。
        client.options.guiScale().set(GUI_SCALE);
        client.resizeGui();
        var screen = new ScrollTab(client);
        client.gui.setScreen(screen);
        var tabs = (FabricCreativeModeInventoryScreen) screen;
        var tab = TechniqueScrollItems.group();
        tabs.switchToPage(tabs.getPage(tab));
        tabs.setSelectedTab(tab);
        return screen.scrollCursor(client);
    }

    private static final class ScrollTab extends CreativeModeInventoryScreen {
        ScrollTab(Minecraft client) {
            super(client.player, client.level.enabledFeatures(), true /* displayOperatorCreativeTab */);
        }

        Vec2 scrollCursor(Minecraft client) {
            // 沿创造页的滚轮查找卷轴，定位后换算为窗口坐标。
            var menu = getMenu();
            var item = TechniqueScrollItems.scrolls().get(SCROLL);
            var slots = menu.slots.subList(0, TAB_SLOTS);
            for (int row = 0; row <= menu.items.size(); row++) {
                for (var slot : slots) {
                    if (slot.getItem().is(item)) {
                        var scale = client.getWindow().getGuiScale();
                        return new Vec2((leftPos + slot.x + SLOT_CENTER) * scale,
                                (topPos + slot.y + SLOT_CENTER) * scale);
                    }
                }
                mouseScrolled(leftPos, topPos, 0, -1);
            }
            throw new AssertionError("功法创造栏未找到尸修卷轴");
        }
    }

    private static void openBook(Minecraft client) {
        // 经物品右键翻开功法谱，选中尸修卷所属的书。
        client.gui.setScreen(null);
        TechniqueCodex.item().use(client.level, client.player, InteractionHand.MAIN_HAND);
        var screen = (CodexScreen) client.gui.screen();
        screen.panel().select(BOOK);
    }

    private static CodexProgress.State state() {
        // 取书页绘制用的同一份卷目录和客户端知识。
        var volume = CodexMoments.catalog().volume(VOLUME).orElseThrow();
        return CodexProgress.current().state(volume);
    }

    private static void sendCorpseKnowledge(MinecraftServer server) {
        // 观察端借黑僵的知识翻书，玩家自身没有学式或换身。
        var player = server.getPlayerList().getPlayers().getFirst();
        var level = player.level();
        var black = UndeadLeapClientTest.spawnCorpse(server, level, player.position());
        var known = CombatFabric.casting().known(black.getUUID(), level.getGameTime());
        ServerPlayNetworking.send(player, CombatChannels.KNOWN.payload(known.toByteArray()));
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        // 等界面至少画出一帧，再由客户端测试框架保存原始截图。
        context.waitTick();
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    }
}
