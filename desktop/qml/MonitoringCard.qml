import QtQuick
import QtQuick.Layouts
ColumnLayout {
    id: operations
    required property var v
    signal settingsRequested()
    signal accountsRequested()
    spacing:18
    Panel {
        Layout.fillWidth:true
        RowLayout {
            Layout.fillWidth:true
            AppText { text:"自动监测";font.pixelSize:16;font.bold:true;Layout.fillWidth:true }
            AppSwitch { objectName:"monitorSwitch";checked:operations.v.monitor;enabled:!operations.v.busy;Accessible.name:"自动监测";onClicked:Backend.toggleMonitor() }
        }
        AppText { text:operations.v.blocked?"等待手动重试，不派发自动请求。":"守护当前连接，断网后按需认证。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true }
        Rectangle {
            Layout.fillWidth:true;implicitHeight:58;radius:10;color:Theme.selected
            RowLayout { anchors.fill:parent;anchors.margins:14;Icon {name:"shield";width:26;height:26} AppText {text:operations.v.blocked?"请求已暂停":operations.v.monitor?"监测已开启":"监测已关闭";color:Theme.blue;font.bold:true;Layout.fillWidth:true} }
        }
        GridLayout {
            Layout.fillWidth:true;columns:2;columnSpacing:10;rowSpacing:10
            AppText {text:"检测间隔";color:Theme.muted;font.pixelSize:12}
            AppText {text:"完成后等待 "+operations.v.interval+" 秒";font.pixelSize:12;Layout.fillWidth:true;horizontalAlignment:Text.AlignRight}
            AppText {text:"上次检测";color:Theme.muted;font.pixelSize:12}
            AppText {text:String(operations.v.lastCheck).replace("T"," ").slice(11,19)||"尚未检测";font.pixelSize:12;Layout.fillWidth:true;horizontalAlignment:Text.AlignRight}
            AppText {text:"本轮耗时";color:Theme.muted;font.pixelSize:12}
            AppText {text:operations.v.lastDuration+" 秒";font.pixelSize:12;Layout.fillWidth:true;horizontalAlignment:Text.AlignRight}
        }
        AppText {visible:operations.v.intervalTooShort;text:"间隔短于最近一轮耗时。结束后再等待，不并发补发。";font.pixelSize:12;color:Theme.amber;Layout.fillWidth:true}
        AppButton {text:"调整检测间隔 →";quiet:true;onClicked:operations.settingsRequested()}
    }
    Panel {
        Layout.fillWidth:true
        AppText {text:"下次认证使用账号";font.bold:true}
        DropDown {
            id:choice;objectName:"accountChoice";Layout.fillWidth:true;enabled:!operations.v.busy
            model:operations.v.accounts.map(a=>(a.label||"校园网账号")+" · "+a.username)
            currentIndex:operations.v.accounts.findIndex(a=>a.id===operations.v.activeAccountId)
            displayText:currentIndex<0?"请选择认证账号":currentText
            Accessible.name:"下次认证使用账号"
            onActivated:index=>Backend.selectAccount(operations.v.accounts[index].id)
        }
        AppText {text:operations.v.session.account&&operations.v.username&&operations.v.session.account!==operations.v.username?"在线："+operations.v.session.account+"；下次认证："+operations.v.username:"选用账号只影响下次认证，不注销现有会话。";color:Theme.muted;font.pixelSize:12;Layout.fillWidth:true}
        AppText {visible:!!operations.v.credentialError;text:operations.v.credentialError;color:Theme.red;font.pixelSize:12;Layout.fillWidth:true}
        AppButton {objectName:"reconnectButton";text:operations.v.blocked?"手动重试":operations.v.busy?"正在处理…":"重新检测并连接";primary:true;Layout.fillWidth:true;enabled:!operations.v.busy;onClicked:Backend.reconnect()}
        AppText {text:operations.v.blocked?"先试一个请求；成功后恢复本次流程。":"主动查询 XHA；已在线时不重复登录。";color:Theme.muted;font.pixelSize:12;Layout.fillWidth:true;horizontalAlignment:Text.AlignHCenter}
        AppButton {text:"管理账号 →";quiet:true;onClicked:operations.accountsRequested()}
    }
}
