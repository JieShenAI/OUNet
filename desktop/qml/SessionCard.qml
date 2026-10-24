import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
Panel {
    id: card
    required property var v
    property bool showHistory: false
    readonly property bool showValues: !!v.session.account && (!v.sessionStale || showHistory)
    signal devicesRequested()
    RowLayout {
        Layout.fillWidth: true
        AppText { text: card.v.sessionStale && card.v.session.account ? "上次校园网会话" : "校园网会话";font.pixelSize:16;font.bold:true;Layout.fillWidth:true }
        Rectangle {
            implicitWidth: stateText.implicitWidth+20;implicitHeight:28;radius:6
            color: card.v.online ? Theme.greenBg : Theme.amberBg
            AppText { id:stateText;anchors.centerIn:parent;font.pixelSize:12;color:card.v.online?Theme.green:Theme.amber;text:card.v.busy?"正在处理":card.v.blocked?"状态待确认":card.v.online?"外网可用":"外网待确认" }
        }
    }
    ColumnLayout {
        Layout.fillWidth:true;spacing:5
        AppText { text:"当前在线身份";color:Theme.muted;font.pixelSize:12 }
        CopyText { text:card.showValues?card.v.session.account:"校园身份尚未读取";font.pixelSize:25;font.bold:true;Layout.fillWidth:true }
        AppText { text:card.v.sessionStale&&card.v.session.account?"历史信息，当前连接身份尚未确认。":"外网连通性与 XHA 在线身份分别确认。";color:Theme.muted;font.pixelSize:12;Layout.fillWidth:true }
        AppButton { visible:card.v.sessionStale&&!!card.v.session.account;text:card.showHistory?"收起上次会话":"展开上次会话";quiet:true;onTriggered:card.showHistory=!card.showHistory }
    }
    Rectangle { height:1;color:Theme.border;Layout.fillWidth:true }
    GridLayout {
        Layout.fillWidth:true;columns:card.width>=700?3:card.width>=380?2:1;uniformCellWidths:true;columnSpacing:18;rowSpacing:20
        Repeater {
            model:[{key:"account",label:"账号"},{key:"online_since",label:"上线时间"},{key:"mobile_identifier",label:"移动端标识"},{key:"source_ipv4",label:"源 IPv4"},{key:"source_ipv6",label:"源 IPv6"},{key:"source_mac",label:"源 MAC"}]
            ColumnLayout {
                required property var modelData
                Layout.fillWidth:true;Layout.minimumWidth:0;Layout.alignment:Qt.AlignTop;spacing:6
                AppText { text:modelData.label;color:Theme.muted;font.pixelSize:12 }
                CopyText { objectName:"session_"+modelData.key;text:card.showValues?(card.v.session[modelData.key]||"页面未提供"):"尚未读取";font.pixelSize:13;Layout.fillWidth:true;Layout.minimumWidth:0 }
            }
        }
    }
    Rectangle { height:1;color:Theme.border;Layout.fillWidth:true }
    RowLayout {
        Layout.fillWidth:true
        AppText { text:card.v.session.fetched_at?"上次读取 "+String(card.v.session.fetched_at).replace("T"," ").slice(0,19):"未请求 XHA · 可主动刷新";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true }
        AppButton { objectName:"refreshSessionButton";text:"刷新信息";quiet:true;busy:card.v.busy;enabled:!card.v.blocked;onTriggered:Backend.refreshSession() }
    }
    AppText { visible:!!card.v.session.message && card.v.session.availability!=="ready";text:card.v.session.message||"";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true }
    AppButton { text:"查看在线设备  →";quiet:true;Layout.fillWidth:true;busy:card.v.busy;enabled:!card.v.blocked;onTriggered:card.devicesRequested() }
}
