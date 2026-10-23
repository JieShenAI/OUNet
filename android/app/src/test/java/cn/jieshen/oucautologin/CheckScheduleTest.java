package cn.jieshen.oucautologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class CheckScheduleTest {
    @Test public void shortChecksFollowStartCadenceIncludingSubMinuteIntervals(){for(int seconds:new int[]{10,30,60,120,3600})assertEquals(1000+seconds*1000L,CheckSchedule.next(1000,9000,seconds));}
    @Test public void OverdueChecksDoNotBuildCatchupBacklog(){assertEquals(45000,CheckSchedule.next(1000,35000,10));assertEquals(21000,CheckSchedule.next(1000,11000,10));}
    @Test public void preservesRealSpacesAndOnlyRemovesApiWrapperQuotes(){assertEquals(" Campus ",CheckSchedule.cleanSsid("\" Campus \""));assertEquals("x\"y",CheckSchedule.cleanSsid("x\"y"));assertEquals("",CheckSchedule.cleanSsid("<unknown ssid>"));}
    @Test public void ssidLimitUsesUtf8BytesAndAcceptsRealSpaces(){SettingsData.validateSsid(" Campus ");SettingsData.validateSsid("OUC-Campus-Research-Building-5F1");try{SettingsData.validateSsid("海海海海海海海海海海海");fail();}catch(IllegalArgumentException expected){}}
    @Test public void invalidSecondValuesAreRejected(){for(int seconds:new int[]{Integer.MIN_VALUE,-1,0,3601,Integer.MAX_VALUE})try{SettingsData.validateInterval(seconds);fail();}catch(IllegalArgumentException expected){}}
}
