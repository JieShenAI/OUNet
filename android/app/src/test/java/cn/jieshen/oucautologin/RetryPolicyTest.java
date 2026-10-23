package cn.jieshen.oucautologin;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class RetryPolicyTest {
    @Test public void thirdAttemptBlocksFurtherAttempts() {
        RetryPolicy state = RetryPolicy.empty().recordFailure(1000000).recordFailure(1120000).recordFailure(1240000);
        assertTrue(state.blocked);
        assertEquals(3, state.failures.size());
        try { state.recordFailure(1360000); fail("Fourth attempt must be blocked"); } catch (IllegalStateException expected) { }
    }
    @Test public void blockDoesNotExpireAfterTenMinutes() {
        RetryPolicy state = RetryPolicy.empty().recordFailure(1000000).recordFailure(1120000).recordFailure(1240000);
        try { state.recordFailure(10000000); fail("Paused state must persist"); } catch (IllegalStateException expected) { }
    }
    @Test public void windowExpiresOnlyBeforeThreshold() {
        RetryPolicy state = RetryPolicy.empty().recordFailure(1000000).recordFailure(1120000).recordFailure(1800001);
        assertFalse(state.blocked); assertEquals(1, state.failures.size());
    }
    @Test public void stateSurvivesReconstructionAndClockRollback() {
        RetryPolicy state = new RetryPolicy(Arrays.asList(1000000L, 1120000L), false).recordFailure(900000);
        assertTrue(state.blocked);
        RetryPolicy restored = new RetryPolicy(state.failures, state.blocked);
        assertTrue(restored.blocked);
    }
    @Test(expected = IllegalArgumentException.class) public void corruptUnblockedStateCannotPermitFourthRequest() {
        new RetryPolicy(Arrays.asList(1L, 2L, 3L), false);
    }
    @Test(expected = IllegalArgumentException.class) public void corruptNegativeTimestampIsRejected() {
        new RetryPolicy(Collections.singletonList(-1L), false);
    }
}
