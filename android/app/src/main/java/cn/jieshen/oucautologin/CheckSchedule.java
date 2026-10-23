package cn.jieshen.oucautologin;

final class CheckSchedule {
    private CheckSchedule(){}
    static long next(long started,long finished,int intervalSeconds){
        SettingsData.validateInterval(intervalSeconds);
        long interval=intervalSeconds*1000L;
        return finished>=started+interval?finished+interval:started+interval;
    }
    static long nextMillis(long started,long finished,int milliseconds){SettingsData.validateMillis(milliseconds);return finished>=started+milliseconds?finished+milliseconds:started+milliseconds;}
    static String cleanSsid(String value){
        if(value==null||value.equals("<unknown ssid>"))return "";
        return value.length()>=2&&value.startsWith("\"")&&value.endsWith("\"")?value.substring(1,value.length()-1):value;
    }
}
