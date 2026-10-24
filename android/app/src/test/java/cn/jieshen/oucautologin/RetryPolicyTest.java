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
        try { state.recordFailure(1254999); fail("Attempt before deadline must be blocked"); } catch (IllegalStateException expected) { }
    }
    @Test public void cooldownAllowsOneTrialAndFailureExtendsIt() {
        RetryPolicy state = RetryPolicy.empty().recordFailure(1000000).recordFailure(1120000).recordFailure(1240000);
        assertTrue(state.canAttempt(1255000));state=state.recordFailure(1255000);
        assertEquals(2,state.level);assertEquals(30000,state.remainingMillis(1255000));assertFalse(state.canAttempt(1255001));
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
    @Test public void backoffCapsAndContinuesUntilRecovery(){
        long now=1000000;RetryPolicy state=RetryPolicy.empty().recordFailure(now).recordFailure(now+1).recordFailure(now+2);now+=2;
        for(long delay:new long[]{15000,30000,60000,120000,300000,300000,300000}){
            assertEquals(delay,state.remainingMillis(now));assertFalse(state.canAttempt(now+delay-1));
            now+=delay;assertTrue(state.canAttempt(now));state=state.recordFailure(now);
        }
        assertEquals(0,RetryPolicy.empty().remainingMillis(now));
    }
    @Test public void reconstructionKeepsDeadlineAndClockRollbackIsBounded(){
        RetryPolicy state=new RetryPolicy(Arrays.asList(1L,2L,3L),true,3,65000);
        RetryPolicy restored=new RetryPolicy(state.failures,state.blocked,state.level,state.nextRetryAt);
        assertEquals(5000,restored.remainingMillis(60000));assertTrue(restored.canAttempt(65000));
        assertEquals(60000,restored.remainingMillis(-100000));
    }
}
