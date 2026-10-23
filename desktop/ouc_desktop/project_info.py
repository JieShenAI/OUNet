"""Public project information, available without a network request."""
from urllib.parse import urlencode

AUTHOR_EMAIL = "shencv@163.com"
REPOSITORY = "JieShenAI/OUNet"
PROJECT_URL = f"https://github.com/{REPOSITORY}"
RELEASES_URL = PROJECT_URL + "/releases"


def contact_url(version):
    return "mailto:" + AUTHOR_EMAIL + "?" + urlencode({
        "subject": f"OUNet 反馈 - Windows {version}",
        "body": "问题描述：\n\n复现步骤：\n\n预期结果：\n\n请勿发送校园网密码或未脱敏日志。",
    })
