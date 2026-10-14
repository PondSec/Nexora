package network.nexora.progression;

/** Pure rules shared by the Elytra event adapter and unit tests. */
public final class InfiniteRocketRules {
    public record Velocity(double x, double y, double z) {}
    private InfiniteRocketRules() {}
    public static boolean eligible(boolean gliding, boolean wearingElytra) { return gliding && wearingElytra; }
    public static boolean cooldownReady(long nowMillis, long readyAtMillis) { return nowMillis >= readyAtMillis; }
    public static long nextReadyAt(long nowMillis, long cooldownMillis) {
        if (nowMillis < 0 || cooldownMillis < 0) throw new IllegalArgumentException("Invalid rocket cooldown time");
        return Math.addExact(nowMillis, cooldownMillis);
    }
    public static Velocity cap(Velocity velocity, double horizontalMaximum, double verticalMaximum) {
        if (velocity == null || !Double.isFinite(horizontalMaximum) || !Double.isFinite(verticalMaximum)
                || horizontalMaximum <= 0 || verticalMaximum <= 0) throw new IllegalArgumentException("Invalid rocket velocity cap");
        double horizontal = Math.hypot(velocity.x(), velocity.z());
        double scale = horizontal > horizontalMaximum ? horizontalMaximum / horizontal : 1.0;
        return new Velocity(velocity.x() * scale, Math.min(verticalMaximum, velocity.y()), velocity.z() * scale);
    }
}
