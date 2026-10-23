package cn.jieshen.oucautologin;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** The selection policy is tested without making any Android Wi-Fi calls. */
public class WifiAccessTest {
    @Test public void headerDistinguishesPermissionFromLocationAndNetworkFailures(){
        WifiAccess.Link hidden=link("",false);java.util.List<WifiAccess.Link> links=Collections.singletonList(hidden);
        assertEquals("没有网络或位置权限",WifiAccess.title(new WifiAccess.Snapshot(links,"","",true,WifiAccess.NameState.MISSING_PERMISSION,false),hidden));
        assertEquals("没有网络或位置权限",WifiAccess.title(new WifiAccess.Snapshot(links,"","",true,WifiAccess.NameState.READY,true),hidden));
        assertEquals("系统定位未开启",WifiAccess.title(new WifiAccess.Snapshot(links,"","",true,WifiAccess.NameState.LOCATION_DISABLED,false),hidden));
        assertEquals("Wi-Fi名称暂不可见",WifiAccess.title(new WifiAccess.Snapshot(links,"","",true,WifiAccess.NameState.REDACTED,false),hidden));
        assertEquals("Wi-Fi已关闭",WifiAccess.title(new WifiAccess.Snapshot(Collections.emptyList(),"","",false),null));
        assertEquals("Wi-Fi未连接",WifiAccess.title(new WifiAccess.Snapshot(Collections.emptyList(),"","",true),null));
        assertEquals("OUC-WIFI",WifiAccess.title(new WifiAccess.Snapshot(links,"","",true),link("OUC-WIFI",false)));
    }
    private WifiAccess.Link link(String name,boolean validated){return new WifiAccess.Link(null,name,validated,false);}
    @Test public void cellularAvailabilityNeverAuthorizesANonTargetWifi(){
        WifiAccess.Snapshot state=new WifiAccess.Snapshot(Collections.singletonList(link("Other",true)),"移动数据可用","",true);
        assertNull(state.target("Campus"));
    }
    @Test public void dualWifiRequiresTheExactNamedLinkNotTheDefaultOrValidatedLink(){
        WifiAccess.Link home=link("Home",true),campus=link("Campus",false);
        WifiAccess.Snapshot state=new WifiAccess.Snapshot(Arrays.asList(home,campus),"Home可用","",true);
        assertSame(campus,state.target("Campus"));assertFalse(state.target("Campus").validated);
    }
    @Test public void sameNameDualWifiIsAmbiguousAndDoesNotSelectTheFirst(){
        WifiAccess.Snapshot state=new WifiAccess.Snapshot(Arrays.asList(link("Campus",true),link("Campus",false)),"移动数据可用","",true);
        assertNull(state.target("Campus"));
    }
    @Test public void unknownNamesAndAnUnconfirmedTargetNeverAuthorizeALink(){
        WifiAccess.Snapshot state=new WifiAccess.Snapshot(Collections.singletonList(link("",true)),"Wi-Fi可用","权限不足",true);
        assertNull(state.target("Campus"));assertNull(state.target(""));assertEquals("名称不可见",state.names());
    }
    @Test public void oneKnownTargetCanBeBoundWithoutGuessingTheOtherUnknownLink(){
        WifiAccess.Link campus=link("Campus",false);
        WifiAccess.Snapshot state=new WifiAccess.Snapshot(Arrays.asList(link("",true),campus),"移动数据可用","",true);
        assertSame(campus,state.target("Campus"));
    }
}
