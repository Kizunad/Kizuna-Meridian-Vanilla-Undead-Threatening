package dev.kizuna.meridian.undead.fabric;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.EntityType;
import org.tomlj.Toml;
import org.tomlj.TomlTable;

record CorpseConfig(Set<EntityType<?>> enabled) {
    static CorpseConfig load(Path path) {
        // 缺文件时把完整的随包配置交给服主；解析成功后整份替换运行中的开关。
        try (var input = CorpseConfig.class.getResourceAsStream("/meridian-undead.toml")) {
            String defaults = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (Files.notExists(path)) {
                Files.createDirectories(path.toAbsolutePath().getParent());
                Files.writeString(path, defaults);
            }
            var packaged = parse(defaults, "meridian-undead.toml");
            var table = parse(Files.readString(path), path.toString());
            var enabled = new HashSet<EntityType<?>>();
            UndeadFabric.SPECIES.forEach((type, key) -> {
                var source = table.contains(key) ? table : packaged;
                if (!(source.get(key) instanceof Boolean value)) {
                    throw new IllegalArgumentException(path + " 的 " + key + " 应为布尔值");
                }
                if (value) {
                    enabled.add(type);
                }
            });
            return new CorpseConfig(Set.copyOf(enabled));
        } catch (IOException failure) {
            throw new UncheckedIOException("读写尸身配置失败：" + path, failure);
        }
    }

    private static TomlTable parse(String text, String origin) {
        // 保留 TOML 的语法诊断，拼错的开关直接指出键名。
        var table = Toml.parse(text);
        if (table.hasErrors()) {
            throw new IllegalArgumentException(origin + "：" + table.errors());
        }
        for (String key : table.keySet()) {
            if (!UndeadFabric.SPECIES.containsValue(key)) {
                throw new IllegalArgumentException(origin + " 包含未知尸身开关：" + key);
            }
        }
        return table;
    }
}
