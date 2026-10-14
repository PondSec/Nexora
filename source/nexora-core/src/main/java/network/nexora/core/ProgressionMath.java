package network.nexora.core;

/** Nonlinear, deterministic level curves shared by player progression and its menus. */
final class ProgressionMath {
    record LevelProgress(int level, long currentXp, long requiredXp, long totalXp, int maxLevel) {}
    record Curve(long base, long linear, long quadratic, int maxLevel) {}
    static final Curve SKY_DEFAULT = new Curve(100, 35, 3, 100);
    static final Curve SKILL_DEFAULT = new Curve(50, 20, 2, 50);
    private ProgressionMath() {}

    static long skyXpToNext(int currentLevel) {
        return required(currentLevel, SKY_DEFAULT);
    }

    static long skillXpToNext(int currentLevel) {
        return required(currentLevel, SKILL_DEFAULT);
    }

    static LevelProgress sky(long totalXp) { return sky(totalXp, SKY_DEFAULT); }
    static LevelProgress sky(long totalXp, Curve curve) { return progress(totalXp, curve, n -> required(n, curve)); }
    static LevelProgress skill(long totalXp) { return skill(totalXp, SKILL_DEFAULT); }
    static LevelProgress skill(long totalXp, Curve curve) { return progress(totalXp, curve, n -> required(n, curve)); }

    static long required(int currentLevel, Curve curve) {
        if (currentLevel < 1 || currentLevel >= curve.maxLevel()) return 0;
        return Math.addExact(curve.base() + curve.linear() * currentLevel,
                Math.multiplyExact(curve.quadratic(), (long) currentLevel * currentLevel));
    }

    private static LevelProgress progress(long totalXp, Curve curve, java.util.function.IntToLongFunction requirement) {
        if (totalXp < 0) throw new IllegalArgumentException("XP cannot be negative");
        int level = 1;
        long remaining = totalXp;
        while (level < curve.maxLevel()) {
            long needed = requirement.applyAsLong(level);
            if (remaining < needed) return new LevelProgress(level, remaining, needed, totalXp, curve.maxLevel());
            remaining -= needed;
            level++;
        }
        return new LevelProgress(curve.maxLevel(), 0, 0, totalXp, curve.maxLevel());
    }
}
