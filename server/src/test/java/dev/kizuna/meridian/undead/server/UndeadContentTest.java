package dev.kizuna.meridian.undead.server;

import dev.kizuna.meridian.core.Anatomy.ConnectionId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.QiTransport;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueAddonResources;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public final class UndeadContentTest {
    private static final ClassLoader LOADER = UndeadContentTest.class.getClassLoader();
    private static final String BASIC = "meridian_basic_techniques";
    private static final String EVOKER = "meridian_evoker";
    private static final String STEP = BASIC + ":light_step";
    private static final String LIVER = EVOKER + ":refine_liver";
    private static final double ATTENTION_RATIO = 0.2;
    private static final double PEARL_FREE_QI = 10;
    private static final double MUSCLE_ENDURANCE = 1_000_000_000;
    private static final double INITIAL_REN_QI = 0.5;
    private static final double INITIAL_PEARL_QI = 1;
    // 小于原器官占位体积可容纳的量，误接到旧器官时也足以实际到账。
    private static final double SHIPMENT_QI = 0.000_001;
    private static final int CHECK_INTERVAL_MILLIS = 100;
    private static final int ADVANCE_MILLIS = 10_000;

    @Test
    public void publishedBundleRegistersAlongsideItsHumanDependency() {
        // 只请求尸身命名空间，实际导出的 pb 必须带上人体依赖，两份模板都应可查。
        var registry = addons();
        assertTrue(HumanContent.bundle().getTemplatesList().contains(adult(registry)));
        assertTrue(UndeadContent.bundle().getTemplatesList().contains(corpse(registry)));
    }

    @Test
    public void humanoidGroupIncludesCorpseWithEveryHumanMeridian() throws IOException {
        // 正式 Java 目录入口通过 META-INF 索引展开成员，同时核对每条同名经脉上的标签。
        var addons = addons();
        var body = corpse(addons);
        var registry = TechniqueRegistry.load(addons, List.of(catalog(BASIC)));
        assertApplies(registry, body, STEP);
        var original = adult(addons).getStructure().getMeridiansList();
        var derived = body.getStructure().getMeridiansList();
        assertEquals(original.size(), derived.size());
        for (var meridian : original) {
            var actual = derived.stream().filter(item -> item.getId().equals(meridian.getId()))
                    .findFirst().orElseThrow();
            assertEquals(meridian.getId(), meridian, actual);
        }
    }

    @Test
    public void legsBindOnCorpseButLiverSlotRequiresTheLivingOrgan() throws IOException {
        // 同境界、同开脉状态下，组内双方都可用目录；炼肝只因本脏槽缺失而失败。
        var addons = addons();
        var human = adult(addons);
        var undead = corpse(addons);
        var registry = TechniqueRegistry.load(addons, List.of(catalog(BASIC), catalog(EVOKER)));
        var lightStep = registry.findLatest(STEP).orElseThrow();
        var refine = registry.findLatest(LIVER).orElseThrow();
        assertApplies(registry, undead, STEP);
        assertApplies(registry, undead, LIVER);
        var living = openSnapshot(human, human);
        var dead = openSnapshot(human, undead);
        assertTrue(TechniqueBinder.bind(living, refine).hasBinding());
        assertTrue(TechniqueBinder.bind(dead, lightStep).hasBinding());
        var rejected = TechniqueBinder.bind(dead, refine);
        assertEquals(rejected.getReason(), TechniqueFailure.MISSING_MODULE, rejected.getFailure());
    }

    @Test
    public void keepsHumanPartsAndTissueIdentities() {
        // 以完整原型作为对照，逐块核对耐力以外的组织字段和部位。
        var registry = addons();
        var human = adult(registry).getStructure();
        var undead = corpse(registry).getStructure();
        assertEquals(human.getPartsList(), undead.getPartsList());
        assertEquals(human.getTissuesCount(), undead.getTissuesCount());
        for (int i = 0; i < human.getTissuesCount(); i++) {
            var original = human.getTissues(i);
            var expected = original.toBuilder();
            if (original.getId().equals("attention_network")) {
                expected.getCapacityBuilder().setEnduranceJoules(original.getCapacity().getEnduranceJoules()
                        * ATTENTION_RATIO);
            } else if (original.getKind().equals("human:muscle")) {
                expected.getCapacityBuilder().setEnduranceJoules(MUSCLE_ENDURANCE);
            }
            assertEquals(original.getId(), expected.build(), undead.getTissues(i));
        }
    }

    @Test
    public void onlyThePearlConnectsToTheMeridians() {
        // 原器官仍有独立的受伤位置；图上只多出尸丹进出任脉的两条边。
        var registry = addons();
        var human = adult(registry).getStructure();
        var undead = corpse(registry).getStructure();
        assertEquals(human.getOrgansCount() + 1, undead.getOrgansCount());
        for (var organ : undead.getOrgansList()) {
            assertFalse(organ.hasIntake());
            assertFalse(organ.hasEndurance());
            var tags = organ.getTagsList();
            assertFalse(tags.contains("human:breathing"));
            assertFalse(tags.stream().anyMatch(tag -> tag.startsWith("human:zang_")));
        }
        for (var original : human.getOrgansList()) {
            var organ = organ(undead, original.getId());
            assertEquals(original.getPartId(), organ.getPartId());
            assertEquals(original.getDisplayName(), organ.getDisplayName());
            assertEquals(organ.getId(), 0, organ.getPortsCount());
            assertEquals(original.getMaxHp(), organ.getMaxHp(), 0);
            assertEquals("meridian_undead:inert_organ", organ.getKind());
            assertTrue(organ.getVolumeCapacity() > 0);
        }
        var pearl = organ(undead, "corpse_pearl");
        assertEquals("meridian_undead:corpse_pearl", pearl.getKind());
        assertEquals("abdomen", pearl.getPartId());
        assertEquals(PEARL_FREE_QI, pearl.getVolumeCapacity() * QiDensity.FREE, 0);
        assertConnections(human, undead);
    }

    private static void assertConnections(BodyStructure human, BodyStructure undead) {
        // 从仍参与连通的组织和经脉一侧重建期望边集，逐条比较人体图纸中的全部边属性。
        var ports = new HashSet<String>();
        human.getTissuesList().forEach(tissue -> tissue.getPortsList().forEach(port -> ports.add(port.getId())));
        human.getMeridiansList().forEach(channel -> channel.getPortsList().forEach(port -> ports.add(port.getId())));
        var inherited = human.getConnectionsList().stream()
                .filter(edge -> ports.contains(edge.getFrom()) && ports.contains(edge.getTo()))
                .toList();
        assertTrue(undead.getConnectionsList().containsAll(inherited));
        assertTrue(connected(undead, "corpse_pearl.out", "ren.in"));
        assertTrue(connected(undead, "ren.out", "corpse_pearl.in"));
        assertEquals(inherited.size() + 2, undead.getConnectionsCount());
    }

    @Test
    public void qiArrivesInThePearlWhileOldOrgansStayEmpty() throws IOException {
        // 真实发运与主动时间结算同时驱动任脉输送和经脉间扩散。
        var registry = addons();
        var open = openSnapshot(adult(registry), corpse(registry));
        var initial = withStoredQi(open);
        var cargo = BodyProtobuf.fromInventory(freeQi(SHIPMENT_QI));
        var shipment = new QiTransport.Request(new ConnectionId("ren.to.corpse_pearl"), cargo, 1);
        var current = QiTransport.start(initial, shipment);
        var clock = current.settledAt();
        for (int millis = CHECK_INTERVAL_MILLIS; millis <= ADVANCE_MILLIS; millis += CHECK_INTERVAL_MILLIS) {
            var target = new TimeCursor(clock.domain(), clock.epoch(), millis);
            var plan = new BodyTimeAdvancement.Plan(target, 1, BodyTimeAdvancement.Mode.ACTIVE);
            current = BodyTimeAdvancement.apply(current, plan).body();
            assertEquals(target, current.settledAt());
            for (var organ : current.organStates()) {
                String id = organ.id().value();
                if (!id.equals("corpse_pearl")) {
                    assertEquals(id + " @ " + millis + " ms", 0, organ.occupiedQi(), 0);
                }
            }
        }
        var pearl = current.organStates().stream().filter(organ -> organ.id().value().equals("corpse_pearl"))
                .findFirst().orElseThrow();
        assertEquals(INITIAL_PEARL_QI + SHIPMENT_QI, pearl.buffer().totalQi(), 0);
    }

    private static BodySnapshot withStoredQi(BodySnapshot body) {
        // 任脉为发运源，尸丹也预存真元；原有的十五个器官从健康空账户开始。
        var snapshot = BodyProtobuf.toMessage(body).toBuilder();
        var ren = snapshot.getMeridianStatesBuilderList().stream().filter(state -> state.getId().equals("ren"))
                .findFirst().orElseThrow();
        ren.setLumenQi(freeQi(INITIAL_REN_QI));
        var pearl = snapshot.getOrganStatesBuilderList().stream().filter(state -> state.getId().equals("corpse_pearl"))
                .findFirst().orElseThrow();
        pearl.setBuffer(freeQi(INITIAL_PEARL_QI));
        return BodyProtobuf.fromMessage(snapshot.build());
    }

    private static QiInventory freeQi(double amount) {
        // 发运与缓存使用同一属性和游离态密度。
        var qi = Qi.newBuilder().setAttribute("meridian:none").setDensity(QiDensity.FREE).setAmount(amount).build();
        return QiInventory.newBuilder().addEntries(qi).build();
    }

    private static AddonRegistry addons() {
        // 通过发布资源而非测试内拼装的内容创建框架注册表。
        return new TechniqueAddonResources(LOADER).registry(Set.of(UndeadContent.NAMESPACE));
    }

    private static BodyTemplate adult(AddonRegistry registry) {
        // 以精确内容版本定位用作基准的人体。
        return registry.findTemplate(HumanContent.TEMPLATE_ID, HumanContent.CONTENT_VERSION).orElseThrow();
    }

    private static BodyTemplate corpse(AddonRegistry registry) {
        // 验证公开内容版本与安装包中登记的一致。
        return registry.findTemplate(UndeadContent.TEMPLATE_ID, UndeadContent.CONTENT_VERSION).orElseThrow();
    }

    private static OrganDefinition organ(BodyStructure structure, String id) {
        // 器官局部 ID 与其种类独立。
        return structure.getOrgansList().stream().filter(organ -> organ.getId().equals(id)).findFirst().orElseThrow();
    }

    private static boolean connected(BodyStructure structure, String from, String to) {
        // 在实际图纸上验证连接方向和两端的局部身份。
        return structure.getConnectionsList().stream().anyMatch(edge -> edge.getFrom().equals(from)
                && edge.getTo().equals(to) && !edge.hasPassiveQiFlow());
    }

    private static TechniqueCatalog catalog(String namespace) throws IOException {
        // 从对应附属安装资源取完整目录，版本和槽要求随正式内容一起变更。
        String path = "data/" + namespace + "/technique_catalog.txtpb";
        try (var in = LOADER.getResourceAsStream(path)) {
            assertNotNull(path, in);
            return TechniqueCatalogManifest.parseText(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void assertApplies(TechniqueRegistry registry, BodyTemplate template, String id) {
        // 使用与运行时学式和起式相同的版本化范围查询。
        var definition = registry.findLatest(id).orElseThrow();
        var reference = TechniqueReference.newBuilder().setId(id).setVersion(definition.getVersion()).build();
        assertTrue(id, registry.applies(reference, BodyProtobuf.fromMessage(template).reference()));
    }

    private static BodySnapshot openSnapshot(BodyTemplate human, BodyTemplate template)
            throws IOException {
        // 用人体出生值建立健康状态，再换成受测图纸、补足尸丹状态并打开经脉。
        var time = BodyMessages.TimeCursor.newBuilder()
                .setDomain("meridian_undead:test")
                .setEpoch("binding")
                .setElapsedMillis(0)
                .build();
        var born = BodyMessages.BodySnapshot.parseFrom(HumanBirth.candidate(human, "binding_body", time));
        var structure = template.getStructure();
        var snapshot = born.toBuilder()
                .setTemplate(template.getReference())
                .setStructure(structure)
                .setRealm(Realm.ESSENCE_TRANSFORMATION)
                .clearOrganStates();
        for (var organ : structure.getOrgansList()) {
            var state = OrganState.newBuilder()
                    .setId(organ.getId())
                    .setHp(organ.getMaxHp())
                    .setBuffer(QiInventory.getDefaultInstance())
                    .build();
            snapshot.addOrganStates(state);
        }
        var opened = MeridianOpening.newBuilder().setStage(MeridianOpeningStage.OPENED).setProgress(1).build();
        snapshot.getMeridianStatesBuilderList().forEach(state -> state.setOpening(opened));
        return BodyProtobuf.fromMessage(snapshot.build());
    }
}
