import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
ColumnLayout {
    id: page
    required property var v
    property string selectedId: ""
    property string initialUser: ""
    property string initialLabel: ""
    property bool adding: false
    property var pendingAction: null
    property bool savingToLeave: false
    // Network polling never locks local drafts or interrupts keyboard/IME focus.
    readonly property bool editingLocked: v.configurationBusy
    readonly property bool dirty: user.text!==initialUser || remark.text!==initialLabel || password.text!==""
    // QVariant account arrays are copied on each status notification. Only
    // replace delegates when their actual content changes, preserving focus.
    readonly property string accountsKey: JSON.stringify(v.accounts)
    readonly property var accountItems: JSON.parse(accountsKey)
    function load(id) {
        selectedId=id;adding=!id
        let a=v.accounts.find(a=>a.id===id)
        initialUser=a?a.username:"";initialLabel=a?a.label||"":""
        user.text=initialUser;remark.text=initialLabel;password.text="";reveal.checked=false
    }
    function leave(action) {if(dirty){pendingAction=action;unsaved.open()}else action()}
    function save() {Backend.saveAccount(selectedId,user.text,password.text,password.text?"replace":"keep",remark.text,ack.checked)}
    Component.onCompleted:load(v.activeAccountId || (v.accounts.length?v.accounts[0].id:""))
    Connections {
        target:Backend
        function onAccountSaved() {
            let a=page.v.accounts.find(a=>a.id===page.selectedId)||page.v.accounts.find(a=>a.username===user.text)
            page.load(a?a.id:"")
            if(page.savingToLeave&&page.pendingAction){page.savingToLeave=false;let action=page.pendingAction;page.pendingAction=null;action()}
        }
    }
    spacing:18
    RowLayout {Layout.fillWidth:true;AppText {text:"账号与密码仅保存在本机，使用当前 Windows 用户加密。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true} AppButton {text:"＋ 添加账号";primary:true;busy:page.editingLocked;onTriggered:page.leave(()=>page.load(""))}}
    GridLayout {
        Layout.fillWidth:true;columns:page.width>=720?2:1;columnSpacing:18;rowSpacing:18
        Panel {
            Layout.preferredWidth:page.width>=720?260:page.width;Layout.fillWidth:page.width<720;Layout.alignment:Qt.AlignTop
            AppText {text:"保存的账号";font.pixelSize:16;font.bold:true}
            AppText {visible:page.v.accounts.length===0;text:"尚无保存的账号。";color:Theme.muted;Layout.fillWidth:true}
            Repeater {
                model:page.accountItems
                AppButton {
                    required property var modelData
                    objectName:"accountRow_"+modelData.id
                    Layout.fillWidth:true;implicitHeight:88;padding:13;busy:page.editingLocked
                    Accessible.name:(modelData.label||"校园网账号")+" "+modelData.username
                    background:Rectangle {radius:10;color:page.selectedId===modelData.id?Theme.selected:Theme.sub;border.width:parent.activeFocus?2:1;border.color:page.selectedId===modelData.id||parent.activeFocus?Theme.blue:Theme.border}
                    contentItem:ColumnLayout {
                        spacing:5
                        AppText {text:(modelData.label||"校园网账号")+(modelData.id===page.v.activeAccountId?" · 已选用":"");font.bold:true;Layout.fillWidth:true}
                        AppText {text:modelData.username.length>7?modelData.username.slice(0,3)+"****"+modelData.username.slice(-3):modelData.username;font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
                        AppText {text:modelData.error||"密码已保存";font.pixelSize:12;color:modelData.error?Theme.red:Theme.green;Layout.fillWidth:true}
                    }
                    onTriggered:page.leave(()=>page.load(modelData.id))
                }
            }
            AppText {text:"上方会话以 XHA 返回为准；选用账号只影响下次认证。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
        }
        Panel {
            Layout.fillWidth:true;Layout.minimumWidth:0;Layout.alignment:Qt.AlignTop
            AppText {text:page.adding?"添加账号":"账号详情";font.pixelSize:16;font.bold:true}
            AppText {text:"备注";font.pixelSize:12;color:Theme.muted}
            Field {id:remark;objectName:"accountLabel";Layout.fillWidth:true;maximumLength:80;enabled:!page.editingLocked;Accessible.name:"账号备注"}
            AppText {text:"校园网账号";font.pixelSize:12;color:Theme.muted}
            Field {id:user;objectName:"accountUsername";Layout.fillWidth:true;maximumLength:128;enabled:!page.editingLocked;Accessible.name:"校园网账号"}
            AppText {text:page.adding?"密码":"密码 · 已加密保存";font.pixelSize:12;color:Theme.muted}
            RowLayout {
                Layout.fillWidth:true
                Field {id:password;objectName:"accountPassword";Layout.fillWidth:true;placeholderText:page.adding?"请输入密码":"********";maximumLength:512;echoMode:reveal.checked?TextInput.Normal:TextInput.Password;enabled:!page.editingLocked;Accessible.name:"校园网密码"}
                AppCheckBox {id:reveal;text:"显示";busy:page.editingLocked;palette.windowText:Theme.ink;Accessible.name:"显示刚输入的密码"}
            }
            AppText {text:page.adding?"密码由 Windows 当前用户加密。":"密码不回填；留空保留该账号的原密码。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
            AppCheckBox {id:ack;visible:!page.v.configured&&!page.v.legacyDirectory;text:"已停止原脚本 / 其他自动认证入口";palette.windowText:Theme.ink;Layout.fillWidth:true}
            Flow {
                Layout.fillWidth:true;spacing:8
                AppButton {text:"保存修改";primary:true;busy:page.v.busy;enabled:user.text.trim().length>0&&(!page.adding||password.text.length>0);onTriggered:page.save()}
                AppButton {text:"用于下次认证";busy:page.v.busy;enabled:!page.adding;onTriggered:page.leave(()=>Backend.selectAccount(page.selectedId))}
                AppButton {text:"删除账号";busy:page.v.busy;enabled:!page.adding;onTriggered:deleteDialog.open()}
            }
        }
    }
    Dialog {
        id:unsaved;anchors.centerIn:Overlay.overlay;modal:true;title:"未保存的修改";width:Math.min(440,Overlay.overlay.width-40)
        background:Rectangle {color:Theme.surface;radius:12;border.color:Theme.border}
        contentItem:ColumnLayout {spacing:16;AppText {text:"保存后继续，或放弃本次修改。";Layout.fillWidth:true} Flow {Layout.fillWidth:true;spacing:8;AppButton {text:"保存并继续";busy:page.v.busy;onTriggered:{page.savingToLeave=true;page.save();unsaved.close()}} AppButton {text:"放弃修改";onTriggered:{page.load(page.selectedId);unsaved.close();let action=page.pendingAction;page.pendingAction=null;if(action)action()}} AppButton {text:"取消";onTriggered:{page.pendingAction=null;unsaved.close()}}}}
    }
    Dialog {
        id:deleteDialog;anchors.centerIn:Overlay.overlay;modal:true;title:"删除本机账号";width:Math.min(440,Overlay.overlay.width-40)
        background:Rectangle {color:Theme.surface;radius:12;border.color:Theme.border}
        contentItem:ColumnLayout {spacing:18;AppText {text:"仅删除此电脑保存的账号和密码，不注销 XHA 在线会话。删除当前选用账号后，需要重新选择认证账号。";Layout.fillWidth:true} RowLayout {AppButton {text:"删除";onTriggered:{Backend.deleteAccount(page.selectedId);deleteDialog.close()}} AppButton {text:"取消";onTriggered:deleteDialog.close()}}}
    }
}
