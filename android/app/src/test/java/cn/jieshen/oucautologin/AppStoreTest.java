package cn.jieshen.oucautologin;

import org.junit.Test;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class AppStoreTest {
    @Test public void credentialErrorSurvivesRestartAndNoteEditButNotARealPasswordChange()throws Exception{
        Fixture f=new Fixture();String id=f.add("student","private-secret");f.store.selectAccount(id);f.store.rememberAuthError(f.store.config(),AuthError.PASSWORD);
        assertEquals(AuthError.PASSWORD,new AppStore(f.memory,f.codec).authError(f.store.config()));
        assertFalse(((String)f.memory.data.get("credential_error")).contains("private-secret"));
        f.store.saveAccount(id,"updated note","student","");assertEquals(AuthError.PASSWORD,f.store.authError(f.store.config()));
        f.store.setIntervalMs(300);assertEquals(AuthError.PASSWORD,f.store.authError(f.store.config()));
        f.store.saveAccount(id,"updated note","student","new-private-secret");assertEquals(AuthError.NONE,f.store.authError(f.store.config()));
    }
    @Test public void credentialErrorNeverLeaksToAnotherAccountAndExplicitResetClearsIt()throws Exception{
        Fixture f=new Fixture();String a=f.add("student-a","a"),b=f.add("student-b","b");f.store.selectAccount(a);f.store.rememberAuthError(f.store.config(),AuthError.ACCOUNT_OR_PASSWORD);
        f.store.selectAccount(b);assertEquals(AuthError.NONE,f.store.authError(f.store.config()));f.store.selectAccount(a);assertEquals(AuthError.ACCOUNT_OR_PASSWORD,f.store.authError(f.store.config()));
        f.store.resetRetry();assertEquals(AuthError.NONE,f.store.authError(f.store.config()));assertNull(f.memory.data.get("credential_error"));
    }
    @Test public void staleCredentialResultCannotReplaceNewAccountsMessage()throws Exception{
        Fixture f=new Fixture();String a=f.add("a","a"),b=f.add("b","b");f.store.selectAccount(a);SettingsData old=f.store.config();f.store.selectAccount(b);
        rejects(()->f.store.rememberAuthError(old,AuthError.PASSWORD));assertEquals(AuthError.NONE,f.store.authError(f.store.config()));
    }
    private static class Memory implements AppStore.Storage {
        final Map<String,Object> data=new HashMap<>();
        boolean fail;
        int commits;
        public Map<String,?> all(){return new HashMap<>(data);}
        public boolean commit(Map<String,Object> updates){
            if(fail)return false;
            commits++;
            for(Map.Entry<String,Object> e:updates.entrySet())if(e.getValue()==null)data.remove(e.getKey());else data.put(e.getKey(),e.getValue());
            return true;
        }
    }
    // Test codec records ownership/byte preservation; actual Android Keystore is a device test.
    private static class Codec implements AppStore.Secrets {
        int iv;
        public String[] encrypt(String value){return new String[]{"test-cipher:"+value,"test-iv-"+(++iv)};}
        public String decrypt(SettingsData.Account a){return a.cipher.substring("test-cipher:".length());}
    }
    private static class Fixture {
        final Memory memory=new Memory();final Codec codec=new Codec();final AppStore store=new AppStore(memory,codec);
        String add(String user,String pass)throws Exception{return store.saveAccount(null,"备注",user,pass);}
        void legacy(){memory.data.put("username","old");memory.data.put("password_cipher","old-encrypted");memory.data.put("password_iv","old-iv");memory.data.put("ssid","Campus");memory.data.put("interval_minutes",2);memory.data.put("enabled",true);}
    }
    private interface Action {void run()throws Exception;}
    private void rejects(Action action)throws Exception {try{action.run();fail("operation must fail closed");}catch(IOException|IllegalArgumentException expected){}}

    @Test public void migrationPreservesCredentialsAndRetiresLegacyAttemptProtection()throws Exception {
        Fixture f=new Fixture();f.legacy();String retry="{\"version\":1,\"blocked\":true,\"attempts\":[1,2,3]}";f.memory.data.put("retry",retry);
        SettingsData c=f.store.config();assertEquals(120,c.intervalSeconds);assertEquals("Campus",c.target);assertTrue(c.enabled);
        assertEquals("old-encrypted",c.active().cipher);assertEquals("old-iv",c.active().iv);assertEquals("legacy-account-v1",c.activeId);
        assertFalse(f.store.retry().blocked);assertEquals(retry,f.memory.data.get("retry_v1_history"));assertNull(f.memory.data.get("retry"));assertEquals(0,f.codec.iv);
        assertEquals(3,f.memory.data.get("schema_version"));assertEquals(2,f.memory.commits);
        f.store.config();assertEquals(2,f.memory.commits);assertEquals("old-encrypted",f.memory.data.get("password_cipher"));
    }
    @Test public void sixtyMinutesMigratesTo3600Seconds()throws Exception {Fixture f=new Fixture();f.legacy();f.memory.data.put("interval_minutes",60);assertEquals(3600,f.store.config().intervalSeconds);}
    @Test public void freshInstallDoesNotTrustPrivateDefault()throws Exception {Fixture f=new Fixture();f.memory.data.put("ssid","HUAWEI-Jie_5G");SettingsData c=f.store.config();assertEquals("",c.target);assertNull(c.active());assertFalse(c.enabled);assertEquals(1,c.intervalSeconds);}
    @Test public void failedMigrationKeepsOriginalDataAndCanRetryOnNewProcess()throws Exception {
        Fixture f=new Fixture();f.legacy();Map<String,Object> old=new HashMap<>(f.memory.data);f.memory.fail=true;
        rejects(f.store::config);assertEquals(old,f.memory.data);assertFalse(f.store.healthy());
        f.memory.fail=false;AppStore restarted=new AppStore(f.memory,f.codec);assertEquals("old-encrypted",restarted.config().active().cipher);
    }
    @Test public void corruptV2NeverOverwritesWithOldOrEmptySettings()throws Exception {
        Fixture f=new Fixture();f.legacy();f.memory.data.put("settings_v2","broken");rejects(f.store::config);assertEquals("broken",f.memory.data.get("settings_v2"));assertEquals(0,f.memory.commits);assertFalse(f.store.configured());
    }
    @Test public void saveThreeAccountsAndSelectionSurviveRestart()throws Exception {
        Fixture f=new Fixture();f.add("a1","one");String b=f.add("b2","two");f.add("c3","three");f.store.selectAccount(b);
        AppStore restarted=new AppStore(f.memory,f.codec);assertEquals(3,restarted.config().accounts.size());assertEquals(b,restarted.config().activeId);assertEquals("two",restarted.password(restarted.config().active()));
    }
    @Test public void newEmptyPasswordCannotBorrowOtherAccountsCipher()throws Exception {Fixture f=new Fixture();f.add("a","secret");rejects(()->f.add("b",""));assertEquals(1,f.store.config().accounts.size());}
    @Test public void editingBlankPasswordRetainsOnlyItsOwnCipherAndIv()throws Exception {
        Fixture f=new Fixture();String a=f.add("a"," 000&pass "),b=f.add("b","other");SettingsData.Account old=f.store.config().find(a);
        f.store.saveAccount(a,"新备注","a","");SettingsData c=f.store.config();assertEquals(old.cipher,c.find(a).cipher);assertEquals(old.iv,c.find(a).iv);
        assertEquals(" 000&pass ",f.store.password(c.find(a)));assertEquals("other",f.store.password(c.find(b)));assertNotEquals(c.find(a).iv,c.find(b).iv);
    }
    @Test public void duplicateUsernameAndRenamingAreRejected()throws Exception {Fixture f=new Fixture();String a=f.add("user","p");rejects(()->f.add(" user ","q"));rejects(()->f.store.saveAccount(a,"","new-user",""));assertEquals(1,f.store.config().accounts.size());}
    @Test public void deletingActiveRequiresExplicitReselectionAndRetainsPause()throws Exception {
        Fixture f=new Fixture();String a=f.add("a","p"),b=f.add("b","q");f.store.setTarget("Campus");f.store.selectAccount(a);f.store.setEnabled(true);
        f.store.recordAuthFailure(f.store.config(),100000);f.store.recordAuthFailure(f.store.config(),160000);f.store.recordAuthFailure(f.store.config(),220000);
        f.store.deleteAccount(a);SettingsData c=f.store.config();assertEquals(1,c.accounts.size());assertEquals(b,c.accounts.get(0).id);assertEquals("",c.activeId);assertFalse(c.enabled);assertTrue(f.store.retry().blocked);
    }
    @Test public void switchingOrAddingCannotBypassGlobalBudget()throws Exception {
        Fixture f=new Fixture();String a=f.add("a","p"),b=f.add("b","q");f.store.selectAccount(a);
        f.store.recordAuthFailure(f.store.config(),100000);f.store.selectAccount(b);f.store.recordAuthFailure(f.store.config(),160000);
        f.add("c","r");f.store.recordAuthFailure(f.store.config(),220000);assertTrue(f.store.retry().blocked);
        f.store.selectAccount(a);rejects(()->f.store.recordAuthFailure(f.store.config(),10000000));assertTrue(f.store.retry().blocked);
    }
    @Test public void staleSnapshotCannotReserveOrClearNewBudget()throws Exception {
        Fixture f=new Fixture();f.add("a","p");SettingsData old=f.store.config();f.store.setTarget("Campus");
        rejects(()->f.store.recordAuthFailure(old,100000));f.store.recordAuthFailure(f.store.config(),100000);
        rejects(()->f.store.resetRetry(old));assertEquals(1,f.store.retry().failures.size());
    }
    @Test public void resetAndRestartPermitImmediateNextFailureWithoutCooldown()throws Exception {
        Fixture f=new Fixture();f.add("a","p");f.store.recordAuthFailure(f.store.config(),100000);f.store.resetRetry();AppStore restarted=new AppStore(f.memory,f.codec);
        restarted.recordAuthFailure(restarted.config(),100001);assertEquals(1,restarted.retry().failures.size());
        restarted.recordAuthFailure(restarted.config(),100002);assertEquals(2,restarted.retry().failures.size());
        assertFalse(f.memory.data.containsKey("last_auth_at"));
    }
    @Test public void corruptNewFailureMetadataStopsRequestsUntilExplicitReset()throws Exception {
        Fixture f=new Fixture();SettingsData c=f.store.config();f.store.retry();f.memory.data.put("retry","broken");rejects(()->f.store.recordAuthFailure(c,100000));
        f.store.resetRetry();assertTrue(f.store.retry().failures.isEmpty());f.store.recordAuthFailure(c,100001);
    }
    @Test public void oldCorruptCooldownAndPauseAreArchivedOnlyOnce()throws Exception {
        Fixture f=new Fixture();f.memory.data.put("retry","old-broken");f.memory.data.put("last_auth_at","old-corrupt-time");
        assertFalse(f.store.retry().blocked);assertEquals("old-broken",f.memory.data.get("retry_v1_history"));assertEquals("old-corrupt-time",f.memory.data.get("auth_v1_history"));
        assertNull(f.memory.data.get("last_auth_at"));int commits=f.memory.commits;f.store.retry();assertEquals(commits,f.memory.commits);
    }
    @Test public void failedReservationDoesNotGrantFutureInMemoryAuthorization()throws Exception {
        Fixture f=new Fixture();SettingsData c=f.store.config();f.memory.fail=true;rejects(()->f.store.recordAuthFailure(c,100000));assertFalse(f.store.isCurrent(c));
        assertFalse(f.memory.data.containsKey("retry"));f.memory.fail=false;rejects(()->f.store.recordAuthFailure(c,160000));
    }
    @Test public void repairArchivesEncryptedSettingsWithoutClearingRetry()throws Exception {
        Fixture f=new Fixture();f.memory.data.put("settings_v2","broken-encrypted-config");f.memory.data.put("retry","broken-retry");f.store.repairSettings();
        assertEquals("broken-encrypted-config",f.memory.data.get("settings_v2_recovery"));assertEquals("broken-retry",f.memory.data.get("retry"));assertTrue(f.store.config().accounts.isEmpty());assertFalse(f.store.enabled());
    }
    @Test public void failureCountPersistsAcrossSwitchButResetImmediatelyClearsIt()throws Exception {
        Fixture f=new Fixture();String a=f.add("user-a","p"),b=f.add("user-b","p");f.store.selectAccount(a);
        f.store.recordAuthFailure(f.store.config(),100000);f.store.selectAccount(b);f.store.recordAuthFailure(f.store.config(),100001);
        assertEquals(2,f.store.retry().failures.size());f.store.resetRetry();f.store.recordAuthFailure(f.store.config(),100002);assertEquals(1,f.store.retry().failures.size());
    }
    @Test public void v2MigrationKeepsCipherRetryAndConvertsSecondsAtomically()throws Exception {
        Fixture f=new Fixture();String id=f.add("user-a","secret");f.store.selectAccount(id);f.store.setInterval(120);
        org.json.JSONObject old=new org.json.JSONObject((String)f.memory.data.get("settings_v2"));old.put("version",2);old.remove("interval_ms");old.put("interval_seconds",120);f.memory.data.put("settings_v2",old.toString());
        f.store.recordAuthFailure(f.store.config(),100000);SettingsData c=f.store.config();assertEquals(120000,c.intervalMs);assertEquals("secret",f.store.password(c.active()));assertEquals(1,f.store.retry().failures.size());
        assertEquals(3,new org.json.JSONObject((String)f.memory.data.get("settings_v2")).getInt("version"));f.store.setIntervalMs(300);assertEquals("0.3",f.store.config().intervalText());
    }
    @Test public void monitorNoLongerNeedsSelectedSsid()throws Exception {Fixture f=new Fixture();String id=f.add("user","pass");f.store.selectAccount(id);f.store.setEnabled(true);assertTrue(f.store.configured());assertTrue(f.store.enabled());assertEquals("",f.store.ssid());}
}
