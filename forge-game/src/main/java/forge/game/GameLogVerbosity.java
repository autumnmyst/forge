package forge.game;

import java.util.EnumSet;
import java.util.Set;

public enum GameLogVerbosity {
    // REVEAL is here rather than only in MEDIUM because a reveal or a notification can be
    // the one thing the player was never shown - their interrupt settings decide whether
    // it stops for them - and the log entry is then the whole of the record. Everything
    // else low verbosity drops either happened in view or can be read off the board.
    LOW("Low",
        EnumSet.of(GameLogEntryType.GAME_OUTCOME, GameLogEntryType.MATCH_RESULTS,
                   GameLogEntryType.TURN, GameLogEntryType.MULLIGAN,
                   GameLogEntryType.ANTE, GameLogEntryType.DAMAGE,
                   GameLogEntryType.REVEAL)),
    MEDIUM("Medium",
        EnumSet.of(GameLogEntryType.GAME_OUTCOME, GameLogEntryType.MATCH_RESULTS,
                   GameLogEntryType.TURN, GameLogEntryType.MULLIGAN,
                   GameLogEntryType.ANTE, GameLogEntryType.DAMAGE,
                   GameLogEntryType.ZONE_CHANGE, GameLogEntryType.REVEAL,
                   GameLogEntryType.LAND,
                   GameLogEntryType.DISCARD, GameLogEntryType.COMBAT,
                   GameLogEntryType.STACK_ADD, GameLogEntryType.STACK_RESOLVE,
                   GameLogEntryType.LIFE)),
    HIGH("High",
        EnumSet.allOf(GameLogEntryType.class)),
    CUSTOM("Custom",
        EnumSet.noneOf(GameLogEntryType.class));

    private final String caption;
    private final Set<GameLogEntryType> includedTypes;

    GameLogVerbosity(String caption, Set<GameLogEntryType> includedTypes) {
        this.caption = caption;
        this.includedTypes = includedTypes;
    }

    public Set<GameLogEntryType> getIncludedTypes() {
        return includedTypes;
    }

    /** Parse from either enum name ("HIGH") or caption ("High"). */
    public static GameLogVerbosity fromString(String value) {
        for (GameLogVerbosity v : values()) {
            if (v.name().equalsIgnoreCase(value) || v.caption.equals(value)) {
                return v;
            }
        }
        return MEDIUM; // safe fallback
    }

    @Override
    public String toString() {
        return caption;
    }
}
