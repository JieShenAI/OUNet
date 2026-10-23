package cn.jieshen.oucautologin;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class WifiTransportTest {
    private static class FakeConnection extends HttpURLConnection {
        boolean disconnected;
        Runnable beforeRead=()->{};
        FakeConnection(URL url){super(url);}
        public void disconnect(){disconnected=true;}
        public boolean usingProxy(){return false;}
        public void connect(){}
        public int getResponseCode()throws IOException{return 200;}
        public String getContentType(){return "text/plain; charset=UTF-8";}
        public InputStream getInputStream(){return new ByteArrayInputStream("Microsoft Connect Test".getBytes(StandardCharsets.UTF_8)){
            public synchronized int read(byte[] bytes,int offset,int count){beforeRead.run();return super.read(bytes,offset,count);}
        };}
    }
    @Test public void totalBudgetAppliesAcrossBodyReadsAndClosesConnection()throws Exception {
        AtomicLong clock=new AtomicLong(0);FakeConnection connection=new FakeConnection(new URL("http://www.msftconnecttest.com/connecttest.txt"));connection.beforeRead=()->clock.addAndGet(9000);
        WifiTransport transport=new WifiTransport(url->connection,clock::get,8000,()->{});
        try{transport.get(connection.getURL().toString());fail();}catch(InterruptedIOException expected){}
        assertTrue(connection.disconnected);assertTrue(connection.getConnectTimeout()<=8000);
    }
    @Test public void configurationGuardPreventsOpeningOrSubmittingAnyRequest()throws Exception {
        AtomicInteger opens=new AtomicInteger();
        WifiTransport transport=new WifiTransport(url->{opens.incrementAndGet();return new FakeConnection(url);},()->0L,10000,()->{throw new InterruptedIOException();});
        try{transport.get("https://xha.ouc.edu.cn/eportal/portal/login?user_password=test");fail();}catch(InterruptedIOException expected){}
        assertEquals(0,opens.get());
    }
    @Test public void cancellationDuringReadDisconnectsAndStopsLaterStages()throws Exception {
        FakeConnection connection=new FakeConnection(new URL("http://www.msftconnecttest.com/connecttest.txt"));
        WifiTransport transport=new WifiTransport(url->connection,()->0L,10000,()->{});connection.beforeRead=transport::cancel;
        try{transport.get(connection.getURL().toString());fail();}catch(InterruptedIOException expected){}assertTrue(connection.disconnected);
        try{transport.get("https://xha.ouc.edu.cn/a79.htm");fail();}catch(InterruptedIOException expected){}
    }
    @Test public void onlyFixedProbeAndSchoolEndpointsAreAllowed()throws Exception {
        for(String value:new String[]{"http://www.msftconnecttest.com/connecttest.txt","http://captive.apple.com/hotspot-detect.html","https://xha.ouc.edu.cn:802/eportal/portal/login?a=b","https://xha.ouc.edu.cn/a41.js"})assertTrue(value,RequestPolicy.allowed(new URL(value)));
        for(String value:new String[]{"http://xha.ouc.edu.cn/a79.htm","https://evil.example/a79.htm","https://xha.ouc.edu.cn.evil/a79.htm","https://user@xha.ouc.edu.cn/a79.htm","https://xha.ouc.edu.cn/logout","http://captive.apple.com/other","https://xha.ouc.edu.cn:0/a79.htm"})assertFalse(value,RequestPolicy.allowed(new URL(value)));
    }
    @Test public void readOnlyProbeDistinguishesRedirectDnsAndCancellation()throws Exception {
        PortalClient redirected=new PortalClient(url->new PortalClient.Response(302,""));assertEquals(PortalClient.ProbeState.CAPTIVE,redirected.probe().state);
        PortalClient dns=new PortalClient(url->{throw new java.net.UnknownHostException();});assertEquals(PortalClient.ProbeState.UNREACHABLE,dns.probe().state);
        PortalClient cancelled=new PortalClient(url->{throw new InterruptedIOException();});try{cancelled.probe();fail();}catch(InterruptedIOException expected){}
    }
    @Test public void dispatchedSchoolFailuresCountButPreDispatchCancellationDoesNot()throws Exception{
        AtomicLong clock=new AtomicLong();RequestControl meter=new RequestControl(clock::get);
        WifiTransport failed=new WifiTransport(url->new FakeConnection(url){@Override public int getResponseCode()throws IOException{throw new java.net.UnknownHostException();}},clock::get,8000,()->{},meter,true);
        try{failed.get(PortalClient.HOST);fail();}catch(IOException expected){}assertEquals(1,meter.total());assertEquals(1,meter.minute());
        WifiTransport cancelled=new WifiTransport(FakeConnection::new,clock::get,8000,()->{throw new InterruptedIOException();},meter,true);
        try{cancelled.get(PortalClient.HOST);fail();}catch(IOException expected){}assertEquals(1,meter.total());
    }
    @Test public void publicProbesNeverReceivePortalRefererOrQuerySecrets()throws Exception{
        FakeConnection c=new FakeConnection(new URL("http://www.msftconnecttest.com/connecttest.txt"));RequestControl meter=new RequestControl(()->0L);
        new WifiTransport(url->c,()->0L,8000,()->{},meter,true).get(c.getURL().toString());assertNull(c.getRequestProperty("Referer"));assertNull(c.getRequestProperty("Cookie"));assertEquals(0,meter.total());assertEquals(1,meter.probeMinute());
        assertFalse(RequestPolicy.allowed(new URL(c.getURL()+"?user_account=secret")));
    }
}
