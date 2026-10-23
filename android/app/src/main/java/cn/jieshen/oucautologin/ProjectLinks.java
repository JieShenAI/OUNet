package cn.jieshen.oucautologin;

import android.content.Intent;
import android.net.Uri;

/** Public, offline project information. Never attaches campus credentials. */
final class ProjectLinks {
    static final String EMAIL = "shencv@163.com";
    static final String REPOSITORY = "https://github.com/JieShenAI/OUNet";
    static final String RELEASES = REPOSITORY + "/releases";
    static Intent contactIntent() {
        String subject = "OUNet 反馈 - Android " + BuildConfig.VERSION_NAME;
        String body = "问题描述：\n\n复现步骤：\n\n预期结果：\n\n请勿发送校园网密码或未脱敏日志。";
        return new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + EMAIL
                + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body)));
    }
    private ProjectLinks() {}
}
