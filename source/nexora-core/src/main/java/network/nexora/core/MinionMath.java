package network.nexora.core;

/** Pure timestamp-based production accounting. No per-minion ticking task is needed. */
final class MinionMath {
    record Advance(int storage, long lastAction) {}

    private MinionMath() {}

    static Advance advance(int stored, long lastAction, long now, long intervalMillis,
                           long offlineCapMillis, int capacity) {
        if (stored < 0 || capacity < 0 || intervalMillis <= 0 || offlineCapMillis < 0) {
            throw new IllegalArgumentException("Invalid minion production state");
        }
        int boundedStorage = Math.min(stored, capacity);
        if (boundedStorage == capacity || now <= lastAction) {
            return new Advance(boundedStorage, Math.max(lastAction, now));
        }
        long effectiveStart = Math.max(lastAction, now - offlineCapMillis);
        long elapsed = Math.max(0, now - effectiveStart);
        long actions = elapsed / intervalMillis;
        int produced = (int) Math.min(actions, capacity - boundedStorage);
        long nextAction = produced >= capacity - boundedStorage
                ? now
                : effectiveStart + actions * intervalMillis;
        return new Advance(boundedStorage + produced, nextAction);
    }

    static long intervalMillis(int tier, int baseSeconds) {
        if (tier < 1 || tier > 11 || baseSeconds < 1) throw new IllegalArgumentException("Invalid tier or interval");
        return Math.max(1_000L, Math.round(baseSeconds * 1_000.0 * Math.pow(0.90, tier - 1)));
    }

    static int capacity(int tier, int perTier) {
        if (tier < 1 || tier > 11 || perTier < 1) throw new IllegalArgumentException("Invalid tier or capacity");
        return Math.multiplyExact(tier + 1, perTier);
    }
}
