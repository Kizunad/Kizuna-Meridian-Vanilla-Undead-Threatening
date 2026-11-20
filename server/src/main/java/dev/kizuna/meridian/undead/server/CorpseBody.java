package dev.kizuna.meridian.undead.server;

import static dev.kizuna.meridian.core.BodySnapshot.CURRENT_SCHEMA_VERSION;

import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.QiDensity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import java.util.Set;

public final class CorpseBody {
    private static final Set<String> VITAL_PARTS = Set.of("head", "neck", "chest");
    private static final double DEATH_HP_RATIO = 0.2;
    private static final QiInventory EMPTY = QiInventory.getDefaultInstance();
    private static final MeridianOpening OPEN = MeridianOpening.newBuilder()
            .setStage(MeridianOpeningStage.OPENED)
            .setProgress(1)
            .build();
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

    public static BodySnapshot born(BodyTemplate template, String bodyId, TimeCursor now, CorpseRank rank) {
        return born(template, bodyId, now, rank, CorpsePhysiology.DEFAULT);
    }

    public static BodySnapshot born(BodyTemplate template, String bodyId, TimeCursor now, CorpseRank rank,
                                    CorpsePhysiology physiology) {
        var vitality = VitalityState.newBuilder()
                .setAgeSeconds(HumanBirth.INITIAL_AGE_YEARS * HumanBirth.SECONDS_PER_YEAR)
                .setDead(false /* dead */)
                .setSpentLifespanSeconds(0)
                .setLifespanLimitSeconds(physiology.lifespanSeconds())
                .build();
        var structure = birthStructure(template.getStructure(), rank);
        var body = BodySnapshot.newBuilder()
                .setSchemaVersion(CURRENT_SCHEMA_VERSION)
                .setRevision(0)
                .setId(bodyId)
                .setTemplate(template.getReference())
                .setStructure(structure)
                .setRealm(rank.realm)
                .setSettledAt(now)
                .setVitality(vitality);
        addHealthyParts(body, structure, rank);
        addEmptyChannels(body, structure, rank);
        return body.build();
    }

    private static BodyStructure birthStructure(BodyStructure template, CorpseRank rank) {
        var structure = template.toBuilder();
        for (var channel : structure.getMeridiansBuilderList()) {
            if (rank.opens(channel.getId())) {
                channel.setLumenVolume(channel.getLumenVolume() + HumanOpening.LUMEN_VOLUME_GAIN);
            }
        }
        structure.getOrgansBuilderList().stream().filter(organ -> organ.getId().equals(UndeadContent.PEARL))
                .findFirst().orElseThrow().setVolumeCapacity(rank.capacity / QiDensity.FREE);
        return structure.build();
    }

    private static QiInventory pearlBuffer(CorpseRank rank) {
        if (rank.startingQi == 0) {
            return EMPTY;
        }
        var entry = Qi.newBuilder()
                .setAttribute("meridian:none")
                .setDensity(QiDensity.FREE)
                .setAmount(rank.startingQi)
                .build();
        return QiInventory.newBuilder().addEntries(entry).build();
    }

    private static void addHealthyParts(BodySnapshot.Builder body, BodyStructure structure, CorpseRank rank) {
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
            var initial = pearlBufferFor(organ, rank);
            body.addOrganStatesBuilder()
                    .setId(organ.getId())
                    .setHp(organ.getMaxHp())
                    .setBuffer(initial);
        }
    }

    private static QiInventory pearlBufferFor(OrganDefinition organ, CorpseRank rank) {
        if (organ.getId().equals(UndeadContent.PEARL)) {
            return pearlBuffer(rank);
        }
        return EMPTY;
    }

    private static void addEmptyChannels(BodySnapshot.Builder body, BodyStructure structure, CorpseRank rank) {
        // 出生既不向区域取元，也不在管腔或外壁生成天然库存。
        for (var meridian : structure.getMeridiansList()) {
            body.addMeridianStatesBuilder()
                    .setId(meridian.getId())
                    .setFatigue(0)
                    .setCondition(MeridianCondition.INTACT)
                    .setOpening(rank.opens(meridian.getId()) ? OPEN : CLOSED)
                    .setLumenQi(EMPTY)
                    .setWallQi(EMPTY);
        }
    }
}
