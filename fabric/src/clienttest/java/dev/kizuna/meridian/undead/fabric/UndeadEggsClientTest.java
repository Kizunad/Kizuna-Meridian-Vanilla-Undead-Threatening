package dev.kizuna.meridian.undead.fabric;

import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameType;

public final class UndeadEggsClientTest implements FabricClientGameTest {
    private static final int EGG_COUNT = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getServer().runOnServer(server ->
                    server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.CREATIVE));
            context.waitFor(client -> client.player.getAbilities().instabuild);
            context.runOnClient(UndeadEggsClientTest::openTab);
            context.waitTick();
            context.takeScreenshot(TestScreenshotOptions.of("undead-eggs").disableCounterPrefix());
        }
    }

    private static void openTab(Minecraft client) {
        var screen = new CreativeModeInventoryScreen(client.player, client.level.enabledFeatures(),
                true /* displayOperatorCreativeTab */);
        client.gui.setScreen(screen);
        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(UndeadSpawnEggs.TAB_ID);
        var pagedScreen = (FabricCreativeModeInventoryScreen) screen;
        pagedScreen.switchToPage(pagedScreen.getPage(tab));
        pagedScreen.setSelectedTab(tab);
        if (pagedScreen.getSelectedTab() != tab) {
            throw new AssertionError("无法打开尸修创造页");
        }
        var eggs = tab.getDisplayItems().stream().map(stack -> stack.getItem()).toList();
        var slots = screen.getMenu().slots;
        var displayed = slots.subList(0, EGG_COUNT).stream().map(slot -> slot.getItem().getItem()).toList();
        if (!displayed.equals(eggs) || !slots.get(EGG_COUNT).getItem().isEmpty()) {
            throw new AssertionError("尸修创造页第一行没有按顺序展示八枚蛋");
        }
    }

}
