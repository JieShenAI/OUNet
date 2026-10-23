"""Only explicit credential rejection is attributed to a password/account."""
import re


def credential_error(message):
    value = re.sub(r"<[^>]*>", " ", str(message)).strip().lower().replace("帐号","账号")
    if re.search(r"(?:账号|用户名|账户)(?:或|和|/)?密码.{0,5}(?:错误|不正确|无效|不匹配|验证失败|校验失败)|(?:invalid|bad) credentials|(?:incorrect|invalid|wrong|bad) (?:username|user name|account) (?:or|and|/) password", value):
        return "账号或密码错误"
    if re.search(r"(?:密码|口令)(?:输入|验证|校验)?(?:错误|不正确|不对|无效|不匹配|验证失败|校验失败)|(?:invalid|incorrect|wrong|bad) (?:password|passwd)|(?:password|passwd)(?: is)?[ :]*(?:error|incorrect|invalid|mismatch|does not match|wrong)", value):
        return "密码错误"
    if re.search(r"(?:账号|用户|用户名)不存在|user(?:name)? (?:does not exist|not found)|user.?not.?exist", value):
        return "账号不存在"
    return ""
