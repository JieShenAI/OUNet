package cn.jieshen.oucautologin;

import android.net.Network;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.LongSupplier;

public final class WifiTransport implements PortalClient.Transport {
    interface Guard {void check()throws IOException;}
    interface ConnectionOpener {HttpURLConnection open(URL url)throws IOException;}
    private final ConnectionOpener opener;
    private final LongSupplier clock;
    private final Guard guard;
    private final RequestControl control;
    private final boolean important;
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    private volatile long deadline;
    WifiTransport(Network network,long deadline,Guard guard){this(network,deadline,guard,true);}
    WifiTransport(Network network,long deadline,Guard guard,boolean important){this(url->(HttpURLConnection)network.openConnection(url,Proxy.NO_PROXY),SystemClock::elapsedRealtime,deadline,guard,RequestControl.GLOBAL,important);}
    WifiTransport(ConnectionOpener opener,LongSupplier clock,long deadline,Guard guard){this(opener,clock,deadline,guard,new RequestControl(clock),true);}
    WifiTransport(ConnectionOpener opener,LongSupplier clock,long deadline,Guard guard,RequestControl control,boolean important){this.opener=opener;this.clock=clock;this.deadline=deadline;this.guard=guard;this.control=control;this.important=important;}
    void deadline(long value){deadline=value;}
    void cancel(){cancelled=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
    private int remaining()throws IOException{
        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("检查已取消");
        guard.check();long time=deadline-clock.getAsLong();
        if(time<=0)throw new InterruptedIOException("本轮请求已到截止时间");
        return(int)Math.min(8000,time);
    }
    @Override public PortalClient.Response get(String value)throws IOException{
        remaining();URL url=new URL(value);
        if(!RequestPolicy.allowed(url))throw new IOException("请求地址不在允许范围，停止请求");
        boolean school=RequestControl.school(url),locked=false;HttpURLConnection c=null;
        try{
            if(school){
                while(!locked){remaining();try{locked=control.schoolLock.tryLock(30,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("请求已取消");}}
            }
            remaining();long wait=control.delay(url,important);if(wait>0)throw new RequestControl.Limited(wait);
            c=opener.open(url);active=c;
            c.setConnectTimeout(remaining());c.setReadTimeout(remaining());c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            c.setRequestProperty("Cache-Control","no-cache");c.setRequestProperty("User-Agent","CampusNetwork/1.3 (Android)");
            if(school)c.setRequestProperty("Referer",PortalClient.HOST+"a79.htm");
            remaining();control.dispatched(url,important);int status=c.getResponseCode();remaining();
            if(status!=200)return new PortalClient.Response(status,"");
            int limit=school?512*1024:4096;ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=c.getInputStream()){
                byte[] buffer=new byte[4096];
                while(true){c.setReadTimeout(remaining());int n=input.read(buffer);if(n<0)break;if(bytes.size()+n>limit)throw new IOException("响应内容过大，停止请求");bytes.write(buffer,0,n);}
            }
            remaining();return new PortalClient.Response(status,decode(bytes.toByteArray(),c.getContentType()));
        }finally{active=null;if(c!=null)c.disconnect();if(locked)control.schoolLock.unlock();}
    }
    static String decode(byte[] bytes,String type){
        String prefix=new String(bytes,0,Math.min(bytes.length,2048),StandardCharsets.ISO_8859_1);
        Matcher match=Pattern.compile("charset\\s*=\\s*['\"]?([\\w-]+)",Pattern.CASE_INSENSITIVE).matcher((type==null?"":type)+" "+prefix);
        if(match.find())try{return new String(bytes,Charset.forName(match.group(1)));}catch(IllegalArgumentException ignored){}
        try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException e){return new String(bytes,Charset.forName("GB18030"));}
    }
}
