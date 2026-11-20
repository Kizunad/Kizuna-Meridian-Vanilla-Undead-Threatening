package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.fabric.HumanoidSpawnEggs;
import dev.kizuna.meridian.humanoid.server.HumanoidSpecies;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class UndeadSpawnEggs {
    static final Identifier TAB_ID = Identifier.fromNamespaceAndPath("meridian_undead", "undead");
    private static final Identifier ICON_ID = itemId("zombie", "hairy");

    private UndeadSpawnEggs() { }

    static void register(List<HumanoidSpecies> species) {
        // 页内按物种 ID 排序，每种先放黑僵蛋，再放毛僵蛋。
        var eggs = new ArrayList<Item>();
        var tooltip = List.<Component>of(Component.translatable("item.meridian_undead.spawn_egg.tooltip"));
        var sorted = species.stream().sorted(Comparator.comparing(HumanoidSpecies::entityId)).toList();
        for (var entry : sorted) {
            String path = Identifier.parse(entry.entityId()).getPath();
            eggs.add(HumanoidSpawnEggs.register(itemId(path, "black"), entry, HumanoidTier.WANDERING_QI, tooltip));
            eggs.add(HumanoidSpawnEggs.register(itemId(path, "hairy"), entry, HumanoidTier.TRUE_QI, tooltip));
        }
        var icon = BuiltInRegistries.ITEM.getOptional(ICON_ID).orElseThrow();
        var tab = FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.meridian_undead.undead"))
                .icon(() -> new ItemStack(icon))
                .displayItems((context, entries) -> eggs.forEach(entries::accept))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB_ID, tab);
    }

    private static Identifier itemId(String species, String rank) {
        return TAB_ID.withPath(species + "_" + rank + "_spawn_egg");
    }
}
