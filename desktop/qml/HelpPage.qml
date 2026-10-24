import QtQuick
import QtQuick.Layouts
ColumnLayout {
    id:page
    required property var v
    signal detailsRequested(string key)
    spacing:18
    Panel {
        Layout.fillWidth:true
        RowLayout {Layout.fillWidth:true;spacing:18;Image {source:"../assets/ounet-desktop.png";sourceSize.width:96;Layout.preferredWidth:64;Layout.preferredHeight:64;fillMode:Image.PreserveAspectFit} ColumnLayout {Layout.fillWidth:true;spacing:5;AppText {text:"OUNet";font.pixelSize:26;font.bold:true} AppText {text:"校园网络助手 · Windows "+page.v.version;Layout.fillWidth:true} AppText {text:"独立开发，非学校官方软件";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}}}
    }
    Panel {
        Layout.fillWidth:true
        AppText {text:"遇到问题";font.pixelSize:16;font.bold:true}
        AppButton {objectName:"diagnosticsButton";text:"诊断记录 · 最新在前  →";quiet:true;onTriggered:page.detailsRequested("diagnostics")}
        AppText {text:"按来源与阶段查看本地记录；查看历史时保持位置。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
        AppButton {text:"导出脱敏排障信息  →";quiet:true;busy:page.v.busy;onTriggered:page.detailsRequested("export")}
        AppButton {text:"打开本机数据目录  ↗";quiet:true;onTriggered:Backend.openLocation("data")}
    }
    Panel {
        Layout.fillWidth:true
        AppText {text:"联系作者";font.pixelSize:16;font.bold:true}
        CopyText {objectName:"authorEmail";text:page.v.authorEmail||"";Layout.fillWidth:true}
        RowLayout {Layout.fillWidth:true
            AppButton {objectName:"sendEmailButton";text:"发送邮件 ↗";quiet:true;onTriggered:Backend.openProjectLink("email")}
            AppButton {objectName:"copyEmailButton";text:"复制邮箱";quiet:true;onTriggered:Backend.copyProjectInfo("email")}
        }
        AppText {text:"通过系统邮件应用联系作者；请勿发送校园网密码或未脱敏日志。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
    }
    Panel {
        Layout.fillWidth:true
        AppText {text:"开源项目";font.pixelSize:16;font.bold:true}
        CopyText {objectName:"projectUrl";text:page.v.projectUrl||"";Layout.fillWidth:true}
        RowLayout {Layout.fillWidth:true
            AppButton {objectName:"openProjectButton";text:"打开项目 ↗";quiet:true;onTriggered:Backend.openProjectLink("source")}
            AppButton {objectName:"copyProjectButton";text:"复制地址";quiet:true;onTriggered:Backend.copyProjectInfo("source")}
        }
    }
    Panel {
        Layout.fillWidth:true
        AppText {text:"了解与更新 OUNet";font.pixelSize:16;font.bold:true}
        Repeater {model:[{key:"intro",title:"软件介绍"},{key:"privacy",title:"数据使用说明"},{key:"copyright",title:"版权与第三方许可"}];AppButton {required property var modelData;text:modelData.title+"  →";quiet:true;onTriggered:page.detailsRequested(modelData.key)}}
        RowLayout {Layout.fillWidth:true;AppText {text:"当前版本 "+page.v.version;Layout.fillWidth:true} AppButton {objectName:"checkUpdateButton";text:"检查 Windows 更新";busy:page.v.busy;enabled:!page.v.blocked;onTriggered:{page.detailsRequested("update");Backend.checkUpdate()}}}
        AppText {text:page.v.blocked?"网络请求已暂停，暂不能检查版本；仍可使用浏览器访问下载页。":"检查本平台最新正式版，下载后先退出托盘进程，再运行安装器覆盖升级。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
        CopyText {text:page.v.releasesUrl||"";Layout.fillWidth:true;font.pixelSize:12}
        RowLayout {Layout.fillWidth:true
            AppButton {objectName:"openReleasesButton";text:"GitHub 下载页 ↗";quiet:true;onTriggered:Backend.openProjectLink("releases")}
            AppButton {text:"复制下载地址";quiet:true;onTriggered:Backend.copyProjectInfo("releases")}
        }
    }
    AppText {text:"免费软件，供学习交流与使用。\n项目自有代码采用 MIT 许可证。";horizontalAlignment:Text.AlignHCenter;font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true;Layout.topMargin:10}
}
