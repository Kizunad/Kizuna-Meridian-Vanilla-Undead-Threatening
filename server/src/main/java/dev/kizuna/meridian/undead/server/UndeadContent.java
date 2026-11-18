package dev.kizuna.meridian.undead.server;

import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.AddonProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.AddonMessages.*;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.server.AddonRegistry;

import java.util.HashSet;

/** 尸身内容包的公开入口，直接复用人体的版本化图纸。 */
public final class UndeadContent {
    public static final String NAMESPACE = "meridian_undead";
    public static final String TEMPLATE_ID = "meridian_undead:corpse";
    public static final int CONTENT_VERSION = 1;
    private static final String INERT_KIND = "meridian_undead:inert_organ";
    private static final String PEARL_KIND = "meridian_undead:corpse_pearl";
    private static final String PEARL = "corpse_pearl";
    // 无端口器官仍须有正的参照体积；千分之一体积在游离态下对应十万分之一元。
    private static final double INERT_ORGAN_VOLUME = 0.001;
    private static final double MUSCLE_ENDURANCE_JOULES = 1_000_000_000;
    private static final double ATTENTION_ENDURANCE_RATIO = 0.2;
    private static final double PEARL_FREE_QI = 10;
    private static final double PEARL_HP = 10;
    private static final double PEARL_THROUGHPUT = 1;
    private static final double PEARL_PATH_METERS = 0.1;

    private UndeadContent() {
        // 模板保存构型，实例资源由身体宿主管理。
    }

    public static AddonBundle bundle() {
        // 精确选择声明依赖的人体修订，同包里的其他模板不参与派生。
        var human = HumanContent.bundle();
        var humanReference = reference(HumanContent.TEMPLATE_ID, HumanContent.CONTENT_VERSION);
        var adult = human.getTemplatesList().stream()
                .filter(template -> template.getReference().equals(humanReference))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("人体内容包缺少指定版本的成人模板"));
        var result = AddonBundle.newBuilder()
                .setSchemaVersion(human.getSchemaVersion())
                .setManifest(manifest(human.getManifest()))
                .addSymbols(organKind(INERT_KIND))
                .addSymbols(organKind(PEARL_KIND))
                .addTemplates(corpse(adult))
                .build();
        AddonProtobuf.validateBundle(result);
        return result;
    }

    private static AddonManifest manifest(AddonManifest human) {
        // 模板继续引用人体的组织种类，依赖范围固定到提供图纸的内容修订。
        var versions = ContentVersionRange.newBuilder()
                .setMinimum(HumanContent.CONTENT_VERSION)
                .setMaximum(HumanContent.CONTENT_VERSION)
                .build();
        var dependency = AddonDependency.newBuilder()
                .setNamespace(HumanContent.NAMESPACE)
                .setVersions(versions)
                .build();
        return AddonManifest.newBuilder()
                .setNamespace(NAMESPACE)
                .setContentVersion(CONTENT_VERSION)
                .setApiVersions(human.getApiVersions())
                .addAllSupportedHosts(human.getSupportedHostsList())
                .addRequiredCapabilities(AddonRegistry.BODY_TEMPLATES)
                .addDependencies(dependency)
                .build();
    }

    private static ContentSymbol organKind(String id) {
        // 配方按器官种类匹配，旧器官与尸丹各有自己的种类。
        return ContentSymbol.newBuilder().setId(id).setKind(ContentSymbolKind.ORGAN_KIND).build();
    }

    private static BodyTemplate corpse(BodyTemplate adult) {
        // 继承人体图纸，在断开原器官后将尸丹接入任脉。
        var structure = adult.getStructure().toBuilder();
        adjustEndurance(structure);
        disconnectOrgans(structure);
        structure.addOrgans(pearl());
        structure.addConnections(connection(PEARL, "ren"));
        structure.addConnections(connection("ren", PEARL));
        return adult.toBuilder()
                .setReference(reference(TEMPLATE_ID, CONTENT_VERSION))
                .setStructure(structure)
                .build();
    }

    private static void disconnectOrgans(BodyStructure.Builder structure) {
        // 以原图纸的端口归属识别连接，保留所有两端都属于其他模块的边。
        var organPorts = new HashSet<String>();
        for (var organ : structure.getOrgansBuilderList()) {
            organ.getPortsList().forEach(port -> organPorts.add(port.getId()));
            organ.setKind(INERT_KIND)
                    .clearIntake()
                    .clearEndurance()
                    .clearTags()
                    .clearPorts()
                    .setVolumeCapacity(INERT_ORGAN_VOLUME);
        }
        var kept = structure.getConnectionsList().stream()
                .filter(edge -> !organPorts.contains(edge.getFrom()) && !organPorts.contains(edge.getTo()))
                .toList();
        structure.clearConnections().addAllConnections(kept);
    }

    private static TemplateReference reference(String id, int version) {
        // 组成员和人体基准都按精确模板修订寻址。
        return TemplateReference.newBuilder().setId(id).setVersion(version).build();
    }

    private static void adjustEndurance(BodyStructure.Builder structure) {
        // 注意力与肌肉各自使用组织做功预算，爆发功率和力量继续来自人体。
        for (var tissue : structure.getTissuesBuilderList()) {
            if (tissue.getKind().equals("human:muscle")) {
                tissue.getCapacityBuilder().setEnduranceJoules(MUSCLE_ENDURANCE_JOULES);
            } else if (tissue.getId().equals("attention_network")) {
                var capacity = tissue.getCapacityBuilder();
                double endurance = capacity.getEnduranceJoules() * ATTENTION_ENDURANCE_RATIO;
                capacity.setEnduranceJoules(endurance);
            }
        }
    }

    private static OrganDefinition pearl() {
        // 容量按游离态拜月所得的元数换算成框架的液态参照体积。
        return OrganDefinition.newBuilder()
                .setId(PEARL)
                .setKind(PEARL_KIND)
                .setDisplayName("尸丹")
                .setPartId("abdomen")
                .setMaxHp(PEARL_HP)
                .setVolumeCapacity(PEARL_FREE_QI / QiDensity.FREE)
                .setThroughputPerSecond(PEARL_THROUGHPUT)
                .addPorts(port(PEARL + ".in", PortDirection.INPUT))
                .addPorts(port(PEARL + ".out", PortDirection.OUTPUT))
                .build();
    }

    private static Port port(String id, PortDirection direction) {
        // 方向和端口名与原有人体经脉遵循同一约定。
        return Port.newBuilder().setId(id).setDirection(direction).build();
    }

    private static Connection connection(String source, String target) {
        // 参照人体本脏支路的端口与距离，搬运由主动请求触发。
        return Connection.newBuilder()
                .setId(source + ".to." + target)
                .setFrom(source + ".out")
                .setTo(target + ".in")
                .setResistance(0)
                .setDistance(PEARL_PATH_METERS)
                .build();
    }
}
