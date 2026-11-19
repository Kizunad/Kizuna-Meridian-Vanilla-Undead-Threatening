package dev.kizuna.meridian.undead.server;

import static dev.kizuna.meridian.core.BodySnapshot.CURRENT_SCHEMA_VERSION;

import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import java.util.Set;

public final class CorpseBody {
    private static final Set<String> VITAL_PARTS = Set.of("head", "neck", "chest");
    private static final double DEATH_HP_RATIO = 0.2;
    private static final QiInventory EMPTY = QiInventory.getDefaultInstance();
    private static final MeridianOpening CLOSED = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.UNOPENED)
            .setProgress(0)
            .build();

    private CorpseBody() { }

    public static boolean lethal(BodySnapshot wounded) {
        // 宿主已扣除本击伤量。生命线只计算部位，器官和组织有独立的损伤账户。
        var parts = wounded.getPartStatesList();
        if (parts.stream().anyMatch(part -> part.getHp() <= 0 && VITAL_PARTS.contains(part.getId()))) {
            return true;
        }
        double hp = parts.stream().mapToDouble(PartState::getHp).sum();
        double maximum = wounded.getStructure().getPartsList().stream().mapToDouble(PartDefinition::getMaxHp).sum();
        return hp < maximum * DEATH_HP_RATIO;
    }

    public static BodySnapshot born(BodyTemplate template, String bodyId, TimeCursor now) {
        // 与类人凡体共用境界和寿命字段，已开经脉数为零。
        var vitality = VitalityState.newBuilder()
                .setAgeSeconds(HumanBirth.INITIAL_AGE_YEARS * HumanBirth.SECONDS_PER_YEAR)
                .setDead(false /* dead */)
                .setSpentLifespanSeconds(0)
                .setLifespanLimitSeconds(HumanBirth.LIFESPAN_YEARS * HumanBirth.SECONDS_PER_YEAR)
                .build();
        var structure = template.getStructure();
        var body = BodySnapshot.newBuilder()
                .setSchemaVersion(CURRENT_SCHEMA_VERSION)
                .setRevision(0)
                .setId(bodyId)
                .setTemplate(template.getReference())
                .setStructure(structure)
                .setRealm(Realm.WANDERING_QI)
                .setSettledAt(now)
                .setVitality(vitality);
        addHealthyParts(body, structure);
        addEmptyChannels(body, structure);
        return body.build();
    }

    private static void addHealthyParts(BodySnapshot.Builder body, BodyStructure structure) {
        // 各账户上限来自尸身图纸，包括腹部新增的尸丹。
        for (var part : structure.getPartsList()) {
            body.addPartStatesBuilder()
                    .setId(part.getId())
                    .setHp(part.getMaxHp());
        }
        for (var tissue : structure.getTissuesList()) {
            body.addTissueStatesBuilder()
                    .setId(tissue.getId())
                    .setHp(tissue.getMaxHp())
                    .setFatigue(0);
        }
        for (var organ : structure.getOrgansList()) {
            body.addOrganStatesBuilder()
                    .setId(organ.getId())
                    .setHp(organ.getMaxHp())
                    .setBuffer(EMPTY);
        }
    }

    private static void addEmptyChannels(BodySnapshot.Builder body, BodyStructure structure) {
        // 出生既不向区域取元，也不在管腔或外壁生成天然库存。
        for (var meridian : structure.getMeridiansList()) {
            body.addMeridianStatesBuilder()
                    .setId(meridian.getId())
                    .setFatigue(0)
                    .setCondition(MeridianCondition.INTACT)
                    .setOpening(CLOSED)
                    .setLumenQi(EMPTY)
                    .setWallQi(EMPTY);
        }
    }
}
