package network.nexora.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressionMathTest {
    @Test void skyblockStartsAtOneAndUsesNonlinearCurveThroughOneHundred() {
        assertEquals(1, ProgressionMath.sky(0).level());
        assertEquals(138, ProgressionMath.skyXpToNext(1));
        assertEquals(2, ProgressionMath.sky(138).level());
        long capXp=0;for(int level=1;level<100;level++)capXp+=ProgressionMath.skyXpToNext(level);
        assertEquals(100,ProgressionMath.sky(capXp).level());
        assertEquals(0,ProgressionMath.sky(capXp).requiredXp());
    }

    @Test void skillsHaveFiftyLevelsAndTheirOwnNonlinearCurve() {
        assertEquals(1,ProgressionMath.skill(0).level());
        assertEquals(72,ProgressionMath.skillXpToNext(1));
        assertEquals(2,ProgressionMath.skill(72).level());
        long capXp=0;for(int level=1;level<50;level++)capXp+=ProgressionMath.skillXpToNext(level);
        assertEquals(50,ProgressionMath.skill(capXp).level());
    }
}
