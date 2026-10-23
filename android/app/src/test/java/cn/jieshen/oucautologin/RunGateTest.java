package cn.jieshen.oucautologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class RunGateTest {
    @Test public void simultaneousTriggersMergeAndReadOnlyCannotDowngradeAuthorizedRun(){
        RunGate gate=new RunGate();RunGate.Ticket t=gate.admit(1,RunGate.READ_ONLY);
        assertSame(t,gate.admit(1,RunGate.AUTOMATIC));assertSame(t,gate.admit(1,RunGate.MANUAL));
        assertSame(t,gate.admit(1,RunGate.READ_ONLY));assertEquals(RunGate.MANUAL,t.mode);
    }
    @Test public void configChangeInvalidatesOldTicketAndLateCompletionCannotClearNewRun(){
        RunGate gate=new RunGate();RunGate.Ticket old=gate.admit(1,RunGate.MANUAL),newer=gate.admit(2,RunGate.READ_ONLY);
        assertTrue(old.cancelled);assertFalse(gate.valid(old,1));gate.finish(old);assertTrue(gate.valid(newer,2));assertEquals(RunGate.READ_ONLY,newer.mode);
    }
    @Test public void cancellationAndCredentialRevisionDoNotReuseAdmission(){
        RunGate gate=new RunGate();RunGate.Ticket old=gate.admit(1,RunGate.AUTOMATIC);gate.cancel();
        assertFalse(gate.valid(old,1));RunGate.Ticket replacement=gate.admit(1,RunGate.READ_ONLY);assertNotSame(old,replacement);assertEquals(RunGate.READ_ONLY,replacement.mode);
        assertFalse(gate.valid(replacement,2));gate.finish(replacement);assertNull(gate.current());
    }
}
