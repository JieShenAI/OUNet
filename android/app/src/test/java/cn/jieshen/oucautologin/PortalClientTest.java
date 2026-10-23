package cn.jieshen.oucautologin;

import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PortalClientTest {
    @Test public void credentialErrorIsClassifiedBeforeSecretRedactionAndOnlyOnExplicitRejection()throws Exception{
        PortalClient.AuthenticationResult bad=new PortalClient(config("{\"login_method\":1}").add("{\"result\":0,\"msg\":\"bad password for user\"}")).authenticate("user","pass");
        assertEquals(AuthError.PASSWORD,bad.error);assertTrue(bad.message.contains("密码错误"));assertFalse(bad.message.contains("pass"));assertFalse(bad.message.contains("user"));
        assertEquals(AuthError.NONE,new PortalClient(config("{\"login_method\":1}").add("{\"msg\":\"密码错误\"}")).authenticate("user","pass").error);
        assertEquals(AuthError.NONE,new PortalClient(config("{\"login_method\":1}").add("{\"result\":1,\"msg\":\"密码错误\"}")).authenticate("user","pass").error);
        assertEquals(AuthError.ACCOUNT_OR_PASSWORD,new PortalClient(config("{\"login_method\":1}").add("{\"result\":0,\"msg\":\"账号或密码错误\"}")).authenticate("user","pass").error);
    }
    @Test public void onlyExplicitRejectionCountsAsAuthenticationFailure()throws Exception{
        assertTrue(new PortalClient(config("{\"login_method\":1}").add("{\"result\":0,\"msg\":\"bad password\"}")).authenticate("user","pass").refused);
        assertFalse(new PortalClient(config("{\"login_method\":1}").add("{\"unexpected\":1}")).authenticate("user","pass").refused);
        assertFalse(new PortalClient(config("{\"login_method\":1}").add("{\"result\":0,\"ret_code\":2,\"msg\":\"IP 已经在线\"}")).authenticate("user","pass").refused);
    }
    private static class Fake implements PortalClient.Transport {
        final List<String> urls = new ArrayList<>();
        final List<PortalClient.Response> replies = new ArrayList<>();
        Fake add(String text) { return add(200, text); }
        Fake add(int status, String text) { replies.add(new PortalClient.Response(status, text)); return this; }
        @Override public PortalClient.Response get(String url) throws IOException {
            urls.add(url);
            if (replies.isEmpty()) throw new IOException("test unavailable");
            return replies.remove(0);
        }
    }
    private Fake config(String data) {
        return new Fake().add("v46ip='10.190.1.2';ss4='00:11:22:33:44:55';vlanid='0';")
            .add("var enHTTPSPort=802; var page={name:'campus_program'};")
            .add("dr1003({\"data\":" + data + "});");
    }
    @Test public void actualPortalUsesMobileTypeAndPrefix() throws Exception {
        Fake fake = config("{\"login_method\":\"1\",\"account_prefix\":\"1\",\"account_suffix\":\"@test\"}").add("dr1003({\"result\":1});");
        String result = new PortalClient(fake).login("student", "zero0&pass");
        assertTrue(result.contains("认证成功")); assertEquals(4, fake.urls.size());
        URL login = new URL(fake.urls.get(3));
        assertEquals("https", login.getProtocol()); assertEquals("xha.ouc.edu.cn", login.getHost());
        assertEquals(802, login.getPort()); assertEquals("/eportal/portal/login", login.getPath());
        String query = URLDecoder.decode(login.getQuery(), "UTF-8");
        assertTrue(query.contains("terminal_type=2")); assertTrue(query.contains("user_account=,1,student@test"));
        assertTrue(query.contains("user_password=zero0&pass")); assertTrue(query.contains("wlan_user_ip=10.190.1.2"));
        assertTrue(query.contains("wlan_user_mac=001122334455")); assertFalse(query.contains("DDDDD"));
    }
    @Test public void oldHttp203IsReportedWithoutCredentials() throws Exception {
        Fake fake = config("{\"login_method\":1}").add(203, "<html>Bad request secret</html>");
        try { new PortalClient(fake).login("student", "secret"); fail(); }
        catch (IOException error) { assertTrue(error.getMessage().contains("HTTP 203")); assertFalse(error.getMessage().contains("secret")); }
    }
    @Test public void unsupportedMethodDoesNotSubmitPassword() throws Exception {
        Fake fake = config("{\"login_method\":14}");
        try { new PortalClient(fake).login("student", "secret"); fail(); } catch (IOException expected) { }
        assertEquals(3, fake.urls.size());
        for (String url : fake.urls) assertFalse(url.contains("user_password"));
    }
    @Test public void htmlAndRedirectsAreNeverConsideredOnline() {
        Fake fake = new Fake().add("<html>Log in</html>").add(302, "");
        assertFalse(new PortalClient(fake).hasInternet());
    }
    @Test public void alternateProviderCanConfirmInternet() {
        Fake fake = new Fake().add(503, "").add("<HTML><TITLE>Success</TITLE><BODY>Success</BODY></HTML>");
        assertTrue(new PortalClient(fake).hasInternet());
    }
    @Test public void alreadyOnlineMessageIsSafeAndRecognized() throws Exception {
        Fake fake = config("{\"login_method\":1}").add("dr1003({\"result\":0,\"ret_code\":2,\"msg\":\"IP 已经在线！\"});");
        assertTrue(new PortalClient(fake).login("student", "secret").contains("已在线"));
    }
    @Test public void invalidIpFallsBackToPortalInference() throws Exception {
        Fake fake = config("{\"login_method\":1}").add("{\"result\":1}");
        fake.replies.set(0, new PortalClient.Response(200, "v46ip='999.2.3.4';"));
        new PortalClient(fake).login("student", "secret");
        assertTrue(fake.urls.get(3).contains("wlan_user_ip=0.0.0.0"));
    }
    @Test public void jsonpIsParsedAsDataOnly() throws Exception {
        assertEquals(1, PortalClient.parseJsonp(" dr1003({\"result\":1}); ").getInt("result"));
        for (String body : new String[]{"<html>Login</html>", "alert(1)", "dr1003([]);", "dr1003({});alert(1)"}) {
            try { PortalClient.parseJsonp(body); fail(body); } catch (IOException expected) { }
        }
    }
    @Test public void credentialsAndUrlsAreRedacted() {
        String value = PortalClient.redact("student secret https://portal/?pwd=secret", "student", "secret");
        assertFalse(value.contains("student")); assertFalse(value.contains("secret")); assertFalse(value.contains("https://"));
    }
    @Test public void onlyExplicitNewAuthenticationCanCountAsAssisted()throws Exception {
        assertTrue(new PortalClient(config("{\"login_method\":1}").add("{\"result\":1}")).authenticate("user","pass").accepted);
        assertFalse(new PortalClient(config("{\"login_method\":1}").add("{\"result\":0,\"ret_code\":2,\"msg\":\"IP 已经在线\"}")).authenticate("user","pass").accepted);
        assertFalse(new PortalClient(config("{\"login_method\":1}").add("{\"result\":0}")).authenticate("user","pass").accepted);
    }
}
