import QtQuick
import QtQuick.Layouts
ColumnLayout {
    id:page
    required property var v
    signal infoRequested(string key)
    spacing:18
    Panel {
        Layout.fillWidth:true
        AppText {text:"连接";font.pixelSize:16;font.bold:true}
        AppText {text:"检测间隔";font.bold:true}
        AppText {text:"每轮完成后再等待设定时间；耗时较长时实际间隔会更长。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
        RowLayout {
            Layout.fillWidth:true
            Field {id:interval;objectName:"intervalInput";text:String(page.v.interval);Layout.preferredWidth:145;maximumLength:14;Accessible.name:"检测间隔秒数"}
            AppText {text:"秒";font.pixelSize:12}
            AppButton {objectName:"applyIntervalButton";text:"应用";enabled:!page.v.busy;onClicked:Backend.setIntervalText(interval.text)}
            Item {Layout.fillWidth:true}
        }
        Flow {
            Layout.fillWidth:true;spacing:8
            Repeater {model:["0.3","1","3","10"];AppButton {required property string modelData;text:modelData+" 秒"+(modelData==="1"?" · 推荐":"");enabled:!page.v.busy;onClicked:{interval.text=modelData;Backend.setIntervalText(modelData)}}}
        }
        AppText {text:page.v.intervalTooShort?"间隔短于最近一轮耗时。结束后再等待，不会并发补发。":"支持 0.3–2147483 秒，最多三位小数；升级保留原设置。";font.pixelSize:12;color:page.v.intervalTooShort?Theme.amber:Theme.muted;Layout.fillWidth:true}
    }
    Panel {
        Layout.fillWidth:true
        AppText {text:"Windows 行为";font.pixelSize:16;font.bold:true}
        Repeater {
            model:[{key:"autostart",title:"登录 Windows 后启动",note:"登录桌面后开始工作，沿用自动监测选择。"},{key:"closeToTray",title:"关闭窗口时留在托盘",note:"完全停止请从托盘菜单选择退出。"},{key:"notifications",title:"重要状态通知",note:"密码错误、请求暂停等新事件只提醒一次。"}]
            RowLayout {
                required property var modelData
                Layout.fillWidth:true;spacing:16
                ColumnLayout {Layout.fillWidth:true;spacing:5;AppText {text:modelData.title;Layout.fillWidth:true} AppText {text:modelData.note;font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}}
                AppSwitch {checked:page.v[modelData.key];enabled:!page.v.busy;Accessible.name:modelData.title;onClicked:Backend.setOption(modelData.key,checked)}
            }
        }
    }
    Panel {
        Layout.fillWidth:true
        AppText {text:"外观与兼容";font.pixelSize:16;font.bold:true}
        RowLayout {Layout.fillWidth:true;AppText {text:"外观";Layout.fillWidth:true} DropDown {Layout.preferredWidth:160;model:["跟随系统","浅色","深色"];currentIndex:["system","light","dark"].indexOf(page.v.appearance);enabled:!page.v.busy;Accessible.name:"外观";onActivated:index=>Backend.setAppearance(["system","light","dark"][index])}}
        RowLayout {Layout.fillWidth:true;AppText {text:"网络名称、定位权限与 WLAN";Layout.fillWidth:true} AppButton {text:"查看说明";onClicked:page.infoRequested("network")}}
        RowLayout {Layout.fillWidth:true;AppText {text:"旧脚本迁移";Layout.fillWidth:true} AppButton {text:"导入旧配置";enabled:!page.v.busy;onClicked:Backend.importLegacy()}}
        AppText {text:"仅处理精确匹配的旧入口，不重新启用已停用任务。已有请求暂停在迁移后保留。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
        AppButton {visible:!!page.v.legacyDirectory;text:"核对旧入口";enabled:!page.v.busy;onClicked:Backend.rescanLegacy()}
    }
}
