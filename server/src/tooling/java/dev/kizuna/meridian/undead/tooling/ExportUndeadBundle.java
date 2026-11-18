package dev.kizuna.meridian.undead.tooling;

import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.undead.server.UndeadContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 将公开内容包导出到类路径约定的位置。 */
public final class ExportUndeadBundle {
    private ExportUndeadBundle() {
        // 导出工具由构建任务调用。
    }

    public static void main(String[] args) throws IOException {
        // 功法库按内容命名空间读取此二进制资源以解析模板组成员。
        var output = Path.of(args[0]).resolve("META-INF/kizuna_meridian/addons/meridian_undead.pb");
        Files.createDirectories(output.getParent());
        Files.write(output, AddonProtobuf.encodeBundle(UndeadContent.bundle()));
    }
}
