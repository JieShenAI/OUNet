package cn.jieshen.oucautologin;

import java.util.Locale;
import java.util.regex.Pattern;

/** Only explicit server wording identifies a credential problem; no guessed error codes. */
enum AuthError {
    NONE, PASSWORD, ACCOUNT_OR_PASSWORD, ACCOUNT;
    String title(){switch(this){case PASSWORD:return "密码错误";case ACCOUNT_OR_PASSWORD:return "账号或密码错误";case ACCOUNT:return "账号错误或不存在";default:return "";}}
    String guidance(){return title()+"：XHA已拒绝这组凭据，请修改账号或密码后重新检测并连接。已暂停这组凭据的自动重试。";}
    private static final String WRONG="(?:错误|不正确|不对|无效|不匹配|不存在|验证失败|校验失败|incorrect|invalid|wrong|bad|mismatch)";
    static AuthError fromMessage(String message){
        String text=message.replaceAll("<[^>]*>"," ").replaceAll("\\s+"," ").toLowerCase(Locale.ROOT).replace("帐号","账号");
        if(Pattern.compile("(?:账号(?:或|和|/)?密码|用户名(?:或|和|/)?密码|账户(?:或|和|/)?密码).{0,5}"+WRONG).matcher(text).find()
            ||Pattern.compile("(?:incorrect|invalid|wrong|bad) (?:username|user name|account) (?:or|and|/) password|(?:username|user name|account) (?:or|and|/) password (?:is )?"+WRONG+"|invalid credentials|bad credentials").matcher(text).find())return ACCOUNT_OR_PASSWORD;
        if(Pattern.compile("(?:密码|口令)(?:输入|验证|校验)?(?:错误|不正确|不对|无效|不匹配|验证失败|校验失败)").matcher(text).find()
            ||Pattern.compile("(?:wrong|bad|invalid|incorrect) (?:password|passwd)|(?:password|passwd)(?: is)?[ :]*(?:wrong|incorrect|invalid|mismatch|error|does not match|not match)").matcher(text).find())return PASSWORD;
        if(Pattern.compile("(?:账号|账户|用户名|用户)(?:输入)?(?:错误|不正确|不存在|无效)").matcher(text).find()
            ||Pattern.compile("(?:unknown|invalid|incorrect|wrong) (?:username|user name|account)|(?:username|user name|account|user) (?:does not exist|not found)").matcher(text).find())return ACCOUNT;
        return NONE;
    }
}
