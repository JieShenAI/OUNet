package cn.jieshen.oucautologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryTrackerTest {
    @Test public void repeatedFailuresAndAccountChangesKeepOneOutage(){
        RecoveryTracker t=new RecoveryTracker();RecoveryTracker.Outage first=t.offline("network:1","test",1000,500);t.attempt("network:1");t.attempt("network:1");
        assertSame(first,t.offline("network:1","test",9000,8500));assertEquals(2,first.attempts);
        t.close(first.id);assertNull(t.current("network:1"));assertNotEquals(first.id,t.offline("network:1","test",10000,9500).id);
    }
    @Test public void connectionChangeCannotRepairAnOldNetwork(){RecoveryTracker t=new RecoveryTracker();t.offline("network:1","same-name",1000,500);t.connection("network:2");assertNull(t.current("network:1"));assertNull(t.current("network:2"));}
    @Test public void staleCloseAndAttemptCannotAffectReplacement(){RecoveryTracker t=new RecoveryTracker();RecoveryTracker.Outage old=t.offline("old","a",1,1),next=t.offline("new","b",2,2);t.close(old.id);t.attempt("old");assertSame(next,t.current("new"));assertEquals(0,next.attempts);}
    @Test public void durationUsesMonotonicClockAndNeverNegative(){RecoveryTracker.Outage o=new RecoveryTracker.Outage("n","s",999999,1000);assertEquals(8000,RecoveryTracker.duration(o,9000));assertEquals(0,RecoveryTracker.duration(o,500));}
    @Test public void recoveryAccountIsMasked(){assertEquals("20****01",RecoveryRepository.mask("20260001"));assertEquals("****",RecoveryRepository.mask("123"));}
}
