package dev.kizuna.meridian.undead.server;

import dev.kizuna.meridian.core.Anatomy.ConnectionId;
import dev.kizuna.meridian.core.Anatomy.OrganId;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.BodyTimeAdvancement;
import dev.kizuna.meridian.core.OrganIntake;
import dev.kizuna.meridian.core.QiTransport;
import dev.kizuna.meridian.core.TimeCursor;
import dev.kizuna.meridian.error.MeridianErrors.IntakeRejected;
import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanContent;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;
import dev.kizuna.meridian.server.AddonRegistry;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.*;
import dev.kizuna.meridian.technique.server.TechniqueAddonResources;
import dev.kizuna.meridian.technique.server.TechniqueAvailability;
import dev.kizuna.meridian.technique.server.TechniqueBinder;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import dev.kizuna.meridian.technique.server.TechniqueRegistry;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class UndeadContentTest {
    private static final ClassLoader LOADER = UndeadContentTest.class.getClassLoader();
    private static final String BASIC = "meridian_basic_techniques";
    private static final String EVOKER = "meridian_evoker";
    private static final String STEP = BASIC + ":light_step";
    private static final double MENTAL_ENDURANCE_RATIO = 0.2;
    private static final double PEARL_FREE_QI = 10;
    private static final double PEARL_HP = 10;
    private static final double MUSCLE_ENDURANCE = 1_000_000_000;
    // 低于人体经脉的自然扩散起运量，可在收回时核对同一批货的完整余额。
    private static final double SHIPMENT_QI = 0.000_001;
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
        assertApplies(registry, body, registry.findLatest(STEP).orElseThrow());
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
    public void fiveRefinementsBindToTheirOwnOrgansAndCanPayTheirWorkCosts() throws IOException {
        var addons = addons();
        var human = adult(addons);
        var undead = corpse(addons);
        var registry = TechniqueRegistry.load(addons, List.of(catalog(BASIC), catalog(EVOKER)));
        var living = openSnapshot(human, human);
        var dead = openSnapshot(human, undead);
        assertTrue(TechniqueBinder.bind(dead, registry.findLatest(STEP).orElseThrow()).hasBinding());
        var organsByForm = Map.of(
                "refine_liver", Set.of("liver"),
                "refine_heart", Set.of("heart"),
                "refine_spleen", Set.of("spleen"),
                "refine_lung", Set.of("left_lung", "right_lung"),
                "refine_kidney", Set.of("left_kidney", "right_kidney"));
        for (var form : organsByForm.entrySet()) {
            String id = EVOKER + ":" + form.getKey();
            var definition = registry.findLatest(id).orElseThrow();
            assertApplies(registry, undead, definition);
            for (var body : List.of(living, dead)) {
                var outcome = TechniqueBinder.bind(body, definition);
                String context = body.template() + " " + id;
                assertTrue(context + " " + outcome.getReason(), outcome.hasBinding());
                var binding = outcome.getBinding();
                var zang = binding.getSlotsList().stream().filter(slot -> slot.getSlotId().equals("zang"))
                        .findFirst().orElseThrow();
                assertEquals(context, form.getValue(), Set.copyOf(zang.getModuleIdsList()));
                assertTrue(context, TechniqueAvailability.workable(body, definition, binding));
            }
        }
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
                        * MENTAL_ENDURANCE_RATIO);
            } else if (original.getKind().equals("human:muscle")) {
                expected.getCapacityBuilder().setEnduranceJoules(MUSCLE_ENDURANCE);
            }
            assertEquals(original.getId(), expected.build(), undead.getTissues(i));
        }
    }

    @Test
    public void corpseInheritsHumanOrgansAndAddsThePearlOnRen() {
        var registry = addons();
        var human = adult(registry).getStructure();
        var undead = corpse(registry).getStructure();
        assertEquals(human.getOrgansCount() + 1, undead.getOrgansCount());
        for (var original : human.getOrgansList()) {
            var expected = original.toBuilder();
            if (original.getKind().equals("human:lung")) {
                expected.clearIntake().clearTags();
                original.getTagsList().stream().filter(tag -> !tag.equals("human:breathing"))
                        .forEach(expected::addTags);
            } else if (original.getKind().equals("human:brain")) {
                double endurance = original.getEndurance().getEnduranceJoules();
                expected.getEnduranceBuilder().setEnduranceJoules(endurance * MENTAL_ENDURANCE_RATIO);
            }
            assertEquals(original.getId(), expected.build(), organ(undead, original.getId()));
        }
        var pearl = organ(undead, "corpse_pearl");
        assertEquals("meridian_undead:corpse_pearl", pearl.getKind());
        assertEquals("abdomen", pearl.getPartId());
        assertEquals(PEARL_HP, pearl.getMaxHp(), 0);
        assertEquals(1, pearl.getThroughputPerSecond(), 0);
        assertEquals(PEARL_FREE_QI, pearl.getVolumeCapacity() * QiDensity.FREE, 0);
        assertEquals(human.getConnectionsCount() + 2, undead.getConnectionsCount());
        assertTrue(undead.getConnectionsList().containsAll(human.getConnectionsList()));
        assertTrue(connected(undead, "corpse_pearl.out", "ren.in"));
        assertTrue(connected(undead, "ren.out", "corpse_pearl.in"));
    }

    @Test
    public void lungsRejectEnvironmentalIntake() throws IOException {
        var registry = addons();
        var body = openSnapshot(adult(registry), corpse(registry));
        var plan = activeInterval(body);
        var incoming = BodyProtobuf.fromInventory(freeQi(SHIPMENT_QI));
        for (String lung : List.of("left_lung", "right_lung")) {
            var id = new OrganId(lung);
            var rejected = assertThrows(lung, IntakeRejected.class, () -> OrganIntake.apply(body, id, incoming, plan));
            assertEquals(lung, FailureCode.INTAKE_UNSUPPORTED, rejected.code());
        }
    }

    @Test
    public void corpseDoesNotPublishBreathingActionsOrProfile() throws IOException {
        var profile = HumanContent.breathing();
        var breathingActions = Set.of(profile.getBodyAction().getId(), profile.getAutomatic().getBodyAction().getId());
        assertFalse(UndeadContent.bundle().getActionsList().stream()
                .anyMatch(action -> breathingActions.contains(action.getId())));
        assertEquals(1, Collections.list(LOADER.getResources(HumanContent.BREATHING_RESOURCE)).size());
        assertNull(LOADER.getResource("META-INF/kizuna_meridian/meridian_undead-breathing.pb"));
    }

    @Test
    public void liverStoresQiAndReturnsItToItsMeridian() throws IOException {
        assertRoundTrip("left_liver_meridian", "liver");
    }

    @Test
    public void pearlStoresQiAndReturnsItToRen() throws IOException {
        assertRoundTrip("ren", "corpse_pearl");
    }

    private static void assertRoundTrip(String meridian, String organ) throws IOException {
        var registry = addons();
        var open = openSnapshot(adult(registry), corpse(registry));
        var snapshot = BodyProtobuf.toMessage(open).toBuilder();
        var cargo = freeQi(SHIPMENT_QI);
        var empty = QiInventory.getDefaultInstance();
        for (var state : snapshot.getMeridianStatesBuilderList()) {
            state.setLumenQi(state.getId().equals(meridian) ? cargo : empty);
        }
        var initial = BodyProtobuf.fromMessage(snapshot.build());
        var received = deliver(initial, meridian, organ);
        assertEquals(SHIPMENT_QI, organBalance(received, organ), 0);
        var stored = BodyTimeAdvancement.apply(received, activeInterval(received)).body();
        assertEquals(SHIPMENT_QI, organBalance(stored, organ), 0);
        var returned = deliver(stored, organ, meridian);
        assertEquals(0, organBalance(returned, organ), 0);
        var channel = returned.meridianStates().stream().filter(state -> state.id().value().equals(meridian))
                .findFirst().orElseThrow();
        assertEquals(SHIPMENT_QI, channel.lumenQi().totalQi(), 0);
    }

    private static double organBalance(BodySnapshot body, String id) {
        return body.organStates().stream().filter(state -> state.id().value().equals(id))
                .findFirst().orElseThrow().buffer().totalQi();
    }

    private static BodySnapshot deliver(BodySnapshot body, String from, String to) {
        var cargo = BodyProtobuf.fromInventory(freeQi(SHIPMENT_QI));
        var request = new QiTransport.Request(new ConnectionId(from + ".to." + to), cargo, 1);
        var sent = QiTransport.start(body, request);
        return BodyTimeAdvancement.apply(sent, activeInterval(sent)).body();
    }

    private static BodyTimeAdvancement.Plan activeInterval(BodySnapshot body) {
        var now = body.settledAt();
        var target = new TimeCursor(now.domain(), now.epoch(), now.elapsedMillis() + ADVANCE_MILLIS);
        return new BodyTimeAdvancement.Plan(target, 1, BodyTimeAdvancement.Mode.ACTIVE);
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

    private static void assertApplies(TechniqueRegistry registry, BodyTemplate template,
                                      TechniqueDefinition technique) {
        // 使用与运行时学式和起式相同的版本化范围查询。
        var reference = TechniqueReference.newBuilder()
                .setId(technique.getId())
                .setVersion(technique.getVersion())
                .build();
        assertTrue(reference.getId(), registry.applies(reference, BodyProtobuf.fromMessage(template).reference()));
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
