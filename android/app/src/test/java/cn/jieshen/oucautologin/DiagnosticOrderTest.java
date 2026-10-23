package cn.jieshen.oucautologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticOrderTest {
    @Test public void latestUsesAppendOrderAcrossYearsClockRollbackAndSameSecond(){
        String log="12-31 23:59:59  old\n01-01 00:00:00  first\n01-01 00:00:00  second\n12-30 00:00:00  clock adjusted\n";
        assertEquals("12-30 00:00:00  clock adjusted\n01-01 00:00:00  second\n01-01 00:00:00  first\n12-31 23:59:59  old",AppStore.newestFirst(log));
        assertEquals("暂无运行记录",AppStore.newestFirst(""));
    }
}
