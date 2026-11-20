package dev.kizuna.meridian.undead.server;

import dev.kizuna.meridian.human.server.HumanBirth;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodyTemplate;
import dev.kizuna.meridian.protocol.pb.BodyMessages.TissueDefinition;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 尸身相对人体的耐力、恢复速度和寿命。 */
public record CorpsePhysiology(double endurance, double muscleRecovery, double healing, double lifespan) {
    public static final CorpsePhysiology DEFAULT = new CorpsePhysiology(2, 1, 1, 2);

    public CorpsePhysiology {
        if (!Double.isFinite(endurance) || endurance <= 0 || !Double.isFinite(lifespan) || lifespan <= 0
                || !Double.isFinite(muscleRecovery) || muscleRecovery < 0 || !Double.isFinite(healing) || healing < 0) {
            throw new IllegalArgumentException("尸身耐力和寿命倍率须为正数，恢复倍率须为非负有限数");
        }
    }

    double lifespanSeconds() {
        return HumanBirth.LIFESPAN_YEARS * HumanBirth.SECONDS_PER_YEAR * lifespan;
    }

    void scaleMuscle(TissueDefinition.Builder muscle) {
        var capacity = muscle.getCapacityBuilder();
        capacity.setEnduranceJoules(capacity.getEnduranceJoules() * endurance);
        double halfLife = 0;
        if (muscleRecovery > 0) {
            halfLife = muscle.getRecoveryHalfLifeSeconds() / muscleRecovery;
        }
        muscle.setRecoveryHalfLifeSeconds(halfLife);
    }

    public BodySnapshot restore(BodySnapshot saved, BodyTemplate template) {
        // 从当前图纸取绝对值，重复读档不叠乘；个体开脉、丹容、疲劳刻度和寿元支出留在原快照。
        if (!saved.getTemplate().equals(template.getReference())) {
            throw new IllegalArgumentException("存档身体与尸身模板不匹配");
        }
        var definitions = template.getStructure().getTissuesList().stream()
                .collect(Collectors.toMap(TissueDefinition::getId, Function.identity()));
        var restored = saved.toBuilder();
        for (var tissue : restored.getStructureBuilder().getTissuesBuilderList()) {
            if (tissue.getKind().equals("human:muscle")) {
                var current = definitions.get(tissue.getId());
                tissue.getCapacityBuilder().setEnduranceJoules(current.getCapacity().getEnduranceJoules());
                tissue.setRecoveryHalfLifeSeconds(current.getRecoveryHalfLifeSeconds());
            }
        }
        restored.getVitalityBuilder().setLifespanLimitSeconds(lifespanSeconds());
        return restored.build();
    }
}
