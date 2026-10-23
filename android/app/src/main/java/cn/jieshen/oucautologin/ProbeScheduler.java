package cn.jieshen.oucautologin;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URL;

/** Provider rotation, cached online evidence, and one request per call. Failures never back off. */
final class ProbeScheduler {
    static final String[] URLS={"http://www.msftconnecttest.com/connecttest.txt","http://captive.apple.com/hotspot-detect.html","http://firefox-portal-detection.com/success.txt"};
    enum State { ONLINE, OFFLINE, UNKNOWN, WAIT }
    static final class Result {final State state;final long waitMs;final boolean fresh;Result(State state,long waitMs){this(state,waitMs,false);}Result(State state,long waitMs,boolean fresh){this.state=state;this.waitMs=waitMs;this.fresh=fresh;}}
    private String key="";private int cursor;private final long[] failedAt={-1,-1,-1};
    private long onlineAt=-1;private boolean pendingFresh,observedCaptive;
    synchronized Result probe(String connection,long now,boolean force,boolean captive,PortalClient.Transport transport,RequestControl control)throws IOException{
        if(!connection.equals(key)){key=connection;cursor=0;onlineAt=-1;pendingFresh=false;observedCaptive=false;for(int i=0;i<3;i++)failedAt[i]=-1;}
        boolean newCaptive=captive&&!observedCaptive;observedCaptive=captive;
        if(!force&&!pendingFresh&&!newCaptive&&onlineAt>=0&&now-onlineAt<15000)return new Result(State.ONLINE,15000-(now-onlineAt));
        int selected=-1;long wait=300;
        for(int n=0;n<3;n++){int i=(cursor+n)%3;long delay=control.delay(new URL(URLS[i]),true);if(delay==0){selected=i;break;}wait=Math.min(wait,delay);}
        if(selected<0)return new Result(State.WAIT,wait);
        int i=selected;cursor=(i+1)%3;
        boolean success=false;
        try{PortalClient.Response response=transport.get(URLS[i]);success=success(i,response);}
        catch(RequestControl.Limited e){return new Result(State.WAIT,e.delayMs);}
        catch(java.net.SocketTimeoutException e){/* a failed dispatched probe */}
        catch(InterruptedIOException e){throw e;}
        catch(IOException e){/* next provider supplies independent evidence */}
        if(success){onlineAt=now;pendingFresh=false;for(int n=0;n<3;n++)failedAt[n]=-1;return new Result(State.ONLINE,0,true);}
        onlineAt=-1;pendingFresh=true;failedAt[i]=now;
        int failures=0;for(long failed:failedAt)if(failed>=0&&now-failed<=10000)failures++;
        return new Result(captive||failures>=2?State.OFFLINE:State.UNKNOWN,0,true);
    }
    synchronized void afterAuthentication(){onlineAt=-1;pendingFresh=true;for(int i=0;i<3;i++)failedAt[i]=-1;}
    static boolean success(int source,PortalClient.Response response){
        if(response.status!=200)return false;String text=response.text.trim();
        if(source==0)return text.equals("Microsoft Connect Test");
        if(source==2)return text.equals("success");
        return text.matches("(?is).*<title>\\s*Success\\s*</title>.*")&&text.matches("(?is).*<body>\\s*Success\\s*</body>.*");
    }
}
