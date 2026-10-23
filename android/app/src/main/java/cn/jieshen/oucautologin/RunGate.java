package cn.jieshen.oucautologin;

/** Shared admission/cancellation rules, independent of Android and HTTP. */
final class RunGate {
    static final int READ_ONLY=0, AUTOMATIC=1, MANUAL=2;
    static final class Ticket {
        final long generation;
        volatile int mode;
        volatile boolean cancelled;
        volatile boolean userReconnect;
        volatile String reason="网络检查";
        Ticket(long generation,int mode){this.generation=generation;this.mode=mode;}
    }
    private Ticket current;
    synchronized Ticket current(){return current;}
    synchronized Ticket admit(long generation,int mode){
        if(current!=null&&!current.cancelled&&current.generation==generation){current.mode=Math.max(current.mode,mode);return current;}
        if(current!=null)current.cancelled=true;
        current=new Ticket(generation,mode);return current;
    }
    synchronized boolean valid(Ticket ticket,long generation){return current==ticket&&!ticket.cancelled&&ticket.generation==generation;}
    synchronized Ticket cancel(){Ticket old=current;if(old!=null)old.cancelled=true;current=null;return old;}
    synchronized void finish(Ticket ticket){if(current==ticket)current=null;}
}
