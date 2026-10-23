package cn.jieshen.oucautologin;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic contract fixtures derived from public scripts, not captured personal records. */
public class SessionInfoClientTest {
    @Test public void authenticationStatusUsesExplicitResultInsteadOfIdentityCompleteness()throws Exception{
        assertEquals(SessionInfoClient.Auth.UNAUTHENTICATED,SessionInfoClient.status(null,new JSONObject("{\"result\":0}")).auth);
        assertEquals(SessionInfoClient.Auth.ONLINE,SessionInfoClient.status(null,new JSONObject("{\"result\":1}")).auth);
        for(String json:new String[]{"{}","{\"result\":true}","{\"result\":2}","{\"uid\":\"some-user\"}"})assertEquals(SessionInfoClient.Auth.UNKNOWN,SessionInfoClient.status(null,new JSONObject(json)).auth);
    }
    private static class Fake implements PortalClient.Transport {
        final List<String> urls=new ArrayList<>(),replies=new ArrayList<>();
        Fake add(String body){replies.add(body);return this;}
        public PortalClient.Response get(String url)throws IOException{urls.add(url);if(replies.isEmpty())throw new IOException();return new PortalClient.Response(200,replies.remove(0));}
    }
    private Fake base(String method){return new Fake().add("v46ip='192.0.2.23';ss4='02:00:00:00:00:23';").add("var enHTTPSPort=802;var page={name:'test_program'};")
        .add("{\"data\":{\"login_method\":1,\"check_online_method\":"+method+",\"page_index\":\"test_page\"}}");}
    private JSONObject record()throws Exception{return new JSONObject("{\"is_owner_ip\":1,\"login_time\":\"2026-10-22 10:18:42\",\"login_ip\":\"192.0.2.23\",\"login_ip_v6\":\"2001:db8::23\",\"mac_address\":\"02:00:00:00:00:23\",\"phone_flag\":1}");}
    private SessionInfoClient.Info select(JSONArray records)throws Exception{return SessionInfoClient.select("actual-user","192.0.2.23","020000000023",records);}
    @Test public void fetchUsesServerIdentityAndNeverLocalCredentials()throws Exception{
        Fake f=base("0").add("dr1003({\"result\":1,\"uid\":\"actual-user\",\"v46ip\":\"192.0.2.23\",\"ss4\":\"020000000023\"});").add(new JSONObject().put("records",new JSONArray().put(record())).toString());
        SessionInfoClient.Info info=new SessionInfoClient(f).fetch();
        assertEquals("actual-user",info.account);assertEquals("2026-10-22 10:18:42",info.since);assertEquals("2001:db8::23",info.ipv6);assertEquals("移动端（1）",info.mobile);assertEquals(5,f.urls.size());
        String query=URLDecoder.decode(f.urls.get(4),"UTF-8");assertTrue(query.contains("user_account=actual-user"));assertTrue(query.contains("page_index=test_page"));
        for(String url:f.urls){assertFalse(url.contains("user_password"));assertFalse(url.contains("terminal_type"));assertFalse(url.contains("/login?"));}
    }
    @Test public void unsupportedModeDoesNotGuessAnotherEndpoint()throws Exception{
        for(String mode:new String[]{"1","\"future\""}){Fake f=base(mode);try{new SessionInfoClient(f).fetch();fail();}catch(IOException expected){}assertEquals(3,f.urls.size());}
    }
    @Test public void missingUidNeverQueriesOtherDevices()throws Exception{Fake f=base("0").add("{\"result\":1}");try{new SessionInfoClient(f).fetch();fail();}catch(IOException expected){}assertEquals(4,f.urls.size());}
    @Test public void uniqueOwnerPreferredAndOtherDeviceNotExposed()throws Exception{
        JSONObject other=record().put("is_owner_ip",0).put("login_ip","192.0.2.55").put("mac_address","020000000055");
        assertEquals("192.0.2.23",select(new JSONArray().put(other).put(record())).ipv4);
    }
    @Test public void noOwnerFallsBackToMatchingIp()throws Exception{JSONObject r=record();r.remove("is_owner_ip");assertEquals("actual-user",select(new JSONArray().put(r)).account);}
    @Test public void multipleOwnersRequireUniqueMac()throws Exception{
        JSONObject other=record().put("mac_address","020000000055");assertEquals("192.0.2.23",select(new JSONArray().put(other).put(record())).ipv4);
        try{select(new JSONArray().put(record()).put(record()));fail();}catch(IOException expected){}
    }
    @Test public void conflictingOwnerOrAccountFailsClosed()throws Exception{
        for(JSONObject r:new JSONObject[]{record().put("login_ip","192.0.2.77"),record().put("mac_address","020000000077"),record().put("uid","another-user")}){try{select(new JSONArray().put(r));fail();}catch(IOException expected){}}
    }
    @Test public void missingFieldsAndPhoneFlagAreNotInvented()throws Exception{
        JSONObject r=record();r.remove("login_time");r.remove("login_ip_v6");r.remove("phone_flag");SessionInfoClient.Info info=select(new JSONArray().put(r));assertEquals(SessionInfoClient.MISSING,info.since);assertEquals(SessionInfoClient.MISSING,info.ipv6);assertEquals(SessionInfoClient.MISSING,info.mobile);
        r.put("phone_flag",0);assertEquals("PC端（0）",select(new JSONArray().put(r)).mobile);r.put("phone_flag","future");assertTrue(select(new JSONArray().put(r)).mobile.startsWith("未识别"));
    }
    @Test public void addressValidationNeverAcceptsHostnameOrPlaceholder(){
        assertEquals("",SessionInfoClient.validAddress("example.com",true));assertEquals("",SessionInfoClient.validAddress("::",true));assertEquals("",SessionInfoClient.validAddress("0.0.0.0",false));assertEquals("",SessionInfoClient.normalizeMac("00:00:00:00:00:00"));assertEquals("2001:db8::1",SessionInfoClient.validAddress("2001:db8::1",true));
    }
    @Test public void decoderSupportsObservedPortalEncodings(){
        String text="上线时间 源IP 移动端标识";byte[] gb=text.getBytes(Charset.forName("GB18030"));assertEquals(text,WifiTransport.decode(gb,"text/javascript"));assertEquals(text,WifiTransport.decode(gb,"text/html; charset=GB18030"));
        String html="<meta charset='GB2312'>上线时间";assertEquals(html,WifiTransport.decode(html.getBytes(Charset.forName("GB2312")),"text/html"));
    }
    @Test public void onlyReadOnlyEndpointsAddedToPolicy()throws Exception{
        assertTrue(RequestPolicy.allowed(new java.net.URL("https://xha.ouc.edu.cn/drcom/chkstatus")));assertTrue(RequestPolicy.allowed(new java.net.URL("https://xha.ouc.edu.cn:802/eportal/portal/page/loadOnlineRecord")));
        assertFalse(RequestPolicy.allowed(new java.net.URL("https://xha.ouc.edu.cn:802/eportal/portal/logout")));assertFalse(RequestPolicy.allowed(new java.net.URL("https://xha.ouc.edu.cn:802/eportal/portal/online_list")));
    }
}
