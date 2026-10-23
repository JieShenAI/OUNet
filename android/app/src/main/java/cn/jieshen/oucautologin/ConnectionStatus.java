package cn.jieshen.oucautologin;

/** Process-local presentation state. Credentials and portal responses never enter this model. */
final class ConnectionStatus {
    enum State { CHECKING, NON_CAMPUS, ONLINE, SIGNED_IN, OFFLINE, CREDENTIAL_ERROR, INDETERMINATE }
    static boolean authenticated(State state){return state==State.ONLINE||state==State.SIGNED_IN;}
    static final class Value {
        final State state; final String key, detail; final long generation;
        Value(State state,String key,long generation,String detail){this.state=state;this.key=key;this.generation=generation;this.detail=detail;}
    }
    private static volatile Value value=new Value(State.CHECKING,"",-1,"正在检测");
    static volatile long waitUntil;
    static Value get(){return value;}
    static void set(State state,String key,long generation,String detail){value=new Value(state,key,generation,detail);}
    static void invalidate(String detail){waitUntil=0;value=new Value(State.CHECKING,"",-1,detail);SessionInfoRepository.invalidate();DeviceRepository.invalidate();}
    private ConnectionStatus(){}
}
