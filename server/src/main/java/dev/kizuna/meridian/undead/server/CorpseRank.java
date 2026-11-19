package dev.kizuna.meridian.undead.server;

import dev.kizuna.meridian.human.server.HumanOpening;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import java.util.Map;

public enum CorpseRank {
    WHITE("白僵", Realm.WANDERING_QI, 10, 0),
    BLACK("黑僵", Realm.WANDERING_QI, 20, 10),
    HAIRY("毛僵", Realm.TRUE_QI, 40, 20);

    static final Map<String, String> CHANNEL_TAGS = Map.of(
            "ren", "meridian_undead:trunk",
            "left_lung_meridian", "meridian_undead:left_arm",
            "right_lung_meridian", "meridian_undead:right_arm",
            "left_stomach_meridian", "meridian_undead:left_leg",
            "right_stomach_meridian", "meridian_undead:right_leg",
            "du", "meridian_undead:head");

    private final String label;
    final Realm realm;
    final double capacity;
    final double startingQi;

    CorpseRank(String label, Realm realm, double capacity, double startingQi) {
        this.label = label;
        this.realm = realm;
        this.capacity = capacity;
        this.startingQi = startingQi;
    }

    public String label() {
        return label;
    }

    boolean opens(String id) {
        return switch (this) {
            case WHITE -> false;
            case BLACK -> CHANNEL_TAGS.containsKey(id) && !id.equals("du");
            case HAIRY -> CHANNEL_TAGS.containsKey(id) || HumanOpening.minimumRealm(id) == Realm.WANDERING_QI;
        };
    }
}
