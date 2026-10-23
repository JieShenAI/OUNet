package cn.jieshen.oucautologin;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuthErrorTest {
    @Test public void specificPasswordAndAmbiguousCredentialMessagesHaveDifferentTitles(){
        for(String s:new String[]{"密码错误","口令不正确","密码校验失败","Incorrect password","bad password","Password is wrong","password mismatch"})assertEquals(s,AuthError.PASSWORD,AuthError.fromMessage(s));
        for(String s:new String[]{"账号或密码错误","帐号密码不对，请重新输入","用户名或密码不正确","invalid username or password","Username or password is incorrect","invalid credentials"})assertEquals(s,AuthError.ACCOUNT_OR_PASSWORD,AuthError.fromMessage(s));
        for(String s:new String[]{"账号不存在","用户名错误","用户不存在","unknown username","user not found"})assertEquals(s,AuthError.ACCOUNT,AuthError.fromMessage(s));
    }
    @Test public void NetworkAndOtherRejectionsAreNeverCalledWrongPassword(){
        for(String s:new String[]{"认证失败","连接超时","服务器繁忙","密码已过期","password expired","余额不足","设备数超限","密码正确，验证码错误","missing password","invalid captcha"})assertEquals(s,AuthError.NONE,AuthError.fromMessage(s));
    }
}
