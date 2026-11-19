package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import dev.kizuna.meridian.humanoid.server.SpeciesBodyRules.Spawn;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.tomlj.Toml;
import org.tomlj.TomlTable;

record CorpseConfig(Set<EntityType<?>> enabled, Chances chances) {
    private static final String BLACK_MIN = "black_chance_min";
    private static final String BLACK_MAX = "black_chance_max";
    private static final String HAIRY_MIN = "hairy_chance_min";
    private static final String HAIRY_MAX = "hairy_chance_max";
    private static final Set<String> CHANCE_KEYS = Set.of(BLACK_MIN, BLACK_MAX, HAIRY_MIN, HAIRY_MAX);

    record Chances(double blackMin, double blackMax, double hairyMin, double hairyMax) {
        // 原版非和平难度可刷出怪物的有效当地难度下限和上限。
        private static final double EASIEST = 0.75;
        private static final double HARDEST = 6.75;
        Chances {
            if (blackMin + hairyMin > 1 || blackMax + hairyMax > 1) {
                throw new IllegalArgumentException("同一难度端点的黑僵和毛僵几率之和不能大于 1");
            }
        }

        HumanoidTier choose(Spawn spawn, RandomGenerator random) {
            if (spawn.requested().isPresent()) {
                return spawn.requested().get();
            }
            if (!EntitySpawnReason.NATURAL.name().equals(spawn.reason())) {
                return HumanoidTier.MORTAL;
            }
            return natural(spawn.difficulty(), random.nextDouble());
        }

        HumanoidTier natural(double localDifficulty, double roll) {
            double difficulty = Math.clamp((localDifficulty - EASIEST) / (HARDEST - EASIEST), 0, 1);
            double black = blackMin + (blackMax - blackMin) * difficulty;
            double hairy = hairyMin + (hairyMax - hairyMin) * difficulty;
            if (roll < black) {
                return HumanoidTier.WANDERING_QI;
            }
            if (roll < black + hairy) {
                return HumanoidTier.TRUE_QI;
            }
            return HumanoidTier.MORTAL;
        }
    }

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
            var chances = new Chances(chance(table, packaged, BLACK_MIN), chance(table, packaged, BLACK_MAX),
                    chance(table, packaged, HAIRY_MIN), chance(table, packaged, HAIRY_MAX));
            return new CorpseConfig(Set.copyOf(enabled), chances);
        } catch (IOException failure) {
            throw new UncheckedIOException("读写尸身配置失败：" + path, failure);
        }
    }

    private static double chance(TomlTable table, TomlTable packaged, String key) {
        var source = table.contains(key) ? table : packaged;
        if (source.get(key) instanceof Number number) {
            double probability = number.doubleValue();
            if (Double.isFinite(probability) && probability >= 0 && probability <= 1) {
                return probability;
            }
        }
        throw new IllegalArgumentException(key + " 应为 0 至 1 的几率");
    }

    private static TomlTable parse(String text, String origin) {
        // 保留 TOML 的语法诊断，拼错的开关直接指出键名。
        var table = Toml.parse(text);
        if (table.hasErrors()) {
            throw new IllegalArgumentException(origin + "：" + table.errors());
        }
        for (String key : table.keySet()) {
            if (!UndeadFabric.SPECIES.containsValue(key) && !CHANCE_KEYS.contains(key)) {
                throw new IllegalArgumentException(origin + " 包含未知尸身开关：" + key);
            }
        }
        return table;
    }
}
