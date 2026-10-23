import QtQuick
import QtQuick.Layouts
ColumnLayout {
    id: page
    required property var v
    property bool wide: width>=900
    property bool expanded: false
    signal devicesRequested()
    signal settingsRequested()
    signal accountsRequested()
    spacing:18
    GridLayout {
        Layout.fillWidth:true;columns:page.wide?2:1;columnSpacing:18;rowSpacing:18
        SessionCard {v:page.v;Layout.row:0;Layout.column:0;Layout.fillWidth:true;Layout.minimumWidth:0;Layout.preferredWidth:page.wide?page.width-312:page.width;onDevicesRequested:page.devicesRequested()}
        MonitoringCard {v:page.v;Layout.column:page.wide?1:0;Layout.row:page.wide?0:1;Layout.rowSpan:page.wide?3:1;Layout.preferredWidth:page.wide?294:page.width;Layout.fillWidth:!page.wide;Layout.alignment:Qt.AlignTop;onSettingsRequested:page.settingsRequested();onAccountsRequested:page.accountsRequested()}
        Panel {
            Layout.row:page.wide?1:2;Layout.column:0;Layout.fillWidth:true;Layout.minimumWidth:0
            RowLayout {
                Layout.fillWidth:true
                ColumnLayout {Layout.fillWidth:true;spacing:5;AppText {text:"本次开机恢复";font.pixelSize:16;font.bold:true} AppText {text:page.v.recovery.known?"开机于 "+String(page.v.recovery.startedAt).replace("T"," ").slice(0,19):"开机范围待确认";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}}
                AppText {text:page.v.recovery.count+"";font.pixelSize:28;font.bold:true;color:Theme.blue}
                AppText {text:"次协助恢复";font.pixelSize:12;color:Theme.muted}
            }
            AppText {visible:page.v.recovery.events.length===0;text:"本次开机尚无助手参与并验证成功的恢复。";color:Theme.muted;Layout.fillWidth:true;font.pixelSize:12}
            Repeater {
                model:page.expanded?page.v.recovery.events:page.v.recovery.events.slice(0,3)
                RowLayout {
                    required property var modelData
                    Layout.fillWidth:true;spacing:13
                    Rectangle {width:7;height:7;radius:4;color:Theme.green;Layout.alignment:Qt.AlignTop;Layout.topMargin:6}
                    AppText {text:String(modelData.recovered_at).replace("T"," ").slice(11,19);font.pixelSize:12;color:Theme.muted;Layout.alignment:Qt.AlignTop}
                    ColumnLayout {Layout.fillWidth:true;spacing:4;AppText {text:modelData.method;Layout.fillWidth:true} AppText {text:"外网稳定复检通过";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}}
                    AppText {text:modelData.duration+" 秒";font.pixelSize:12;color:Theme.muted}
                }
            }
            AppButton {text:page.expanded?"收起恢复详情 ↑":"展开恢复详情 ↓";quiet:true;onClicked:page.expanded=!page.expanded}
            AppText {visible:page.expanded;text:"同次开机重开软件保留记录；同一故障只计一次。网络自行恢复不加入协助恢复总数。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
        }
        Panel {
            Layout.row:page.wide?2:3;Layout.column:0;Layout.fillWidth:true;Layout.minimumWidth:0
            RowLayout {
                Layout.fillWidth:true
                ColumnLayout {Layout.fillWidth:true;AppText {text:"XHA 调用统计";font.bold:true} AppText {text:"实际派发的校园网请求尝试，含失败。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}}
                ColumnLayout {AppText {objectName:"processRequestCount";text:String(page.v.requestStats.total);font.pixelSize:25;font.bold:true} AppText {text:"本次进程";font.pixelSize:12;color:Theme.muted}}
                ColumnLayout {Layout.leftMargin:12;AppText {text:String(page.v.requestStats.recent);font.pixelSize:25;font.bold:true} AppText {text:"最近 60 秒";font.pixelSize:12;color:Theme.muted}}
            }
            Rectangle {height:1;color:Theme.border;Layout.fillWidth:true}
            AppText {text:"进程始于 "+String(page.v.requestStats.startedAt).replace("T"," ").slice(0,19)+" · 公共探测不计入 · 联网监测不访问 XHA";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
        }
    }
    AppText {text:page.v.message;color:page.v.blocked?Theme.amber:Theme.muted;font.pixelSize:12;Layout.fillWidth:true}
    AppText {visible:!!page.v.systemWarning;text:page.v.systemWarning;color:Theme.amber;font.pixelSize:12;Layout.fillWidth:true}
}
