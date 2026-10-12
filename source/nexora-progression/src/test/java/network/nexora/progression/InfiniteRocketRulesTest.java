package network.nexora.progression;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InfiniteRocketRulesTest {
    @Test void requiresBothElytraAndActiveGlideAndUsesOneCooldownClock() {
        assertFalse(InfiniteRocketRules.eligible(false,true));
        assertFalse(InfiniteRocketRules.eligible(true,false));
        assertTrue(InfiniteRocketRules.eligible(true,true));
        assertEquals(2_500, InfiniteRocketRules.nextReadyAt(1_000,1_500));
        assertTrue(InfiniteRocketRules.cooldownReady(2_500,2_500));
        assertFalse(InfiniteRocketRules.cooldownReady(2_499,2_500));
    }

    @Test void capsHorizontalVectorMagnitudeAndUpwardVelocityWithoutIncreasingDownwardSpeed() {
        var capped = InfiniteRocketRules.cap(new InfiniteRocketRules.Velocity(3,5,4),2,1.5);
        assertEquals(1.2,capped.x(),1e-12);
        assertEquals(1.5,capped.y(),1e-12);
        assertEquals(1.6,capped.z(),1e-12);
        var falling = InfiniteRocketRules.cap(new InfiniteRocketRules.Velocity(0,-3,0),2,1.5);
        assertEquals(-3,falling.y());
    }
}
