import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import "."
ApplicationWindow {
    id:root
    width:1200;height:820;minimumWidth:640;minimumHeight:560
    visible:false;title:"OUNet";color:Theme.bg
    font.family:"Microsoft YaHei UI"
    palette.window:Theme.bg;palette.windowText:Theme.ink;palette.text:Theme.ink;palette.base:Theme.surface;palette.button:Theme.surface;palette.buttonText:Theme.ink
    property var v:Backend.data
    property string page:"overview"
    property string detailKey:""
    property string detailTitle:""
    property string detailText:""
    property var previousFocus:null
    property var logEntries:[]
    property int newLogs:0
    property string logFilter:"all"
    readonly property bool fullNav:width>=1008
    readonly property bool railNav:width>=760
    readonly property var navigation:[{key:"overview",label:"连接概览",icon:"home"},{key:"account",label:"账号管理",icon:"user"},{key:"settings",label:"偏好设置",icon:"settings"},{key:"help",label:"帮助与关于",icon:"help"}]
    function go(target){
        let action=function(){detailDrawer.close();page=target;mainScroll.contentItem.contentY=0;heading.forceActiveFocus()}
        if(page==="account")accountsPage.leave(action);else action()
    }
    function openDetails(key){
        previousFocus=root.activeFocusItem;detailKey=key
        let content={intro:["软件介绍","OUNet 识别当前连接，在需要时协助认证。普通监测外网通过后直接结束，不访问 XHA；校园在线身份可由用户主动刷新。关闭到托盘后仍可运行，退出才会停止。"],privacy:["数据使用说明","账号与记录保存在本机。密码使用 Windows 当前用户 DPAPI 加密，界面不回填已存密码。认证必需的凭据仅提交至已核实的 XHA HTTPS 接口。公共探测与用户手动检查更新向各自服务请求，不夹带校园账号和密码。没有自建遥测或广告后台。"],copyright:["版权与第三方许可","免费软件，供学习交流与使用。项目自有代码采用 MIT 许可证。非学校官方软件。Python、Qt / PySide、requests 与打包工具的许可见安装目录 licenses 和 THIRD_PARTY_NOTICES.md；软件介绍与说明可离线阅读。"],network:["网络名称与权限","应用只读显示实际连接，不扫描或切换 Wi-Fi。SSID 不可见可能来自定位权限或 WLAN 服务；能确认接口与 IPv4 时仍可检测。多个无线接口或地址无法确认时不提交密码。校园流量绑定同一源 IPv4 与 Windows 接口；IPv6-only、有线校园认证仍待独立验证。"],export:["导出脱敏排障信息","导出只包含本地阶段与结果。密码、账号、地址和 URL 会脱敏；不会导出完整服务端响应、登录 URL 或密码密文。请选择文件保存位置，分享前仍可自行检查文本。"],devices:["在线设备",""],diagnostics:["诊断记录",""],update:["Windows 更新","只读取此仓库的 Windows 正式版本元数据；不混入 Android APK。有效下载来源与 SHA-256 会在此显示，由用户在浏览器下载和安装，不静默执行程序。"]}
        let item=content[key]||["说明",""];detailTitle=item[0];detailText=item[1]
        if(key==="diagnostics"){logEntries=Backend.diagnosticEntries();newLogs=0}
        detailDrawer.open()
        if(key==="devices")Backend.loadDevices(false)
    }
    function refreshLogs(force){
        let entries=Backend.diagnosticEntries()
        if(force||detailsScroll.contentItem.contentY<=2){logEntries=entries;newLogs=0;if(force)detailsScroll.contentItem.contentY=0}
        else {let first=logEntries.length?logEntries[0].sequence||0:0;newLogs=entries.filter(e=>(e.sequence||0)>first).length}
    }
    onClosing:close=>{if(v.closeToTray&&v.trayAvailable&&!v.migrationPending){close.accepted=false;root.hide();Backend.windowHidden()}else{close.accepted=false;quitDialog.open()}}
    Component.onCompleted:{let screen=Qt.application.screens[0];if(screen&&!v.demo){width=Math.max(minimumWidth,Math.min(width,screen.desktopAvailableWidth-48));height=Math.max(minimumHeight,Math.min(height,screen.desktopAvailableHeight-48))}}
    Shortcut {sequence:"Ctrl+1";enabled:!detailDrawer.opened;onActivated:root.go("overview")}
    Shortcut {sequence:"Ctrl+2";enabled:!detailDrawer.opened;onActivated:root.go("account")}
    Shortcut {sequence:"Ctrl+3";enabled:!detailDrawer.opened;onActivated:root.go("settings")}
    Shortcut {sequence:"Ctrl+4";enabled:!detailDrawer.opened;onActivated:root.go("help")}
    Shortcut {sequence:"Ctrl+R";enabled:!detailDrawer.opened&&root.page==="overview"&&!v.busy&&!v.blocked;onActivated:Backend.refreshSession()}
    Timer {interval:1000;running:root.visible;repeat:true;onTriggered:Backend.refreshLocalView()}
    Connections {
        target:Backend
        function onNotice(message){noticeLabel.text=message;noticeBox.visible=true;noticeTimer.restart()}
        function onMigrationReady(){migrationDialog.open()}
    }
    RowLayout {
        anchors.fill:parent;spacing:0
        Rectangle {
            visible:root.railNav;Layout.preferredWidth:root.fullNav?204:72;Layout.fillHeight:true;color:Theme.sub
            ColumnLayout {
                anchors.fill:parent;anchors.margins:root.fullNav?14:9;spacing:8
                RowLayout {
                    Layout.fillWidth:true;Layout.topMargin:20;Layout.bottomMargin:30;spacing:12
                    Image {source:"../assets/ounet-desktop.png";Layout.preferredWidth:40;Layout.preferredHeight:40;Layout.leftMargin:root.fullNav?10:6;sourceSize.width:80;fillMode:Image.PreserveAspectFit}
                    ColumnLayout {visible:root.fullNav;spacing:2;AppText {text:"OUNet";font.pixelSize:23;font.bold:true} AppText {text:"校园网络助手";font.pixelSize:12;color:Theme.muted}}
                }
                AppText {visible:root.fullNav;text:"工作空间";font.pixelSize:12;color:Theme.muted;Layout.leftMargin:14;Layout.bottomMargin:8}
                Repeater {
                    model:root.navigation
                    Button {
                        required property var modelData
                        Layout.fillWidth:true;implicitHeight:44;hoverEnabled:true
                        Accessible.name:modelData.label
                        ToolTip.visible:hovered&&!root.fullNav;ToolTip.text:modelData.label
                        background:Rectangle {radius:8;color:root.page===modelData.key?Theme.selected:parent.hovered?Theme.selected:"transparent";border.width:parent.activeFocus?2:0;border.color:Theme.blue;Rectangle {visible:root.page===modelData.key;width:3;height:18;radius:2;color:Theme.blue;anchors.left:parent.left;anchors.verticalCenter:parent.verticalCenter}}
                        contentItem:RowLayout {spacing:13;Icon {name:modelData.icon;tone:root.page===modelData.key?"blue":"muted";Layout.leftMargin:root.fullNav?12:8} AppText {visible:root.fullNav;text:modelData.label;Layout.fillWidth:true;color:root.page===modelData.key?Theme.blue:Theme.ink;font.bold:root.page===modelData.key}}
                        onClicked:root.go(modelData.key)
                    }
                }
                Item {Layout.fillHeight:true}
                ColumnLayout {visible:root.fullNav;Layout.margins:10;spacing:8;AppText {text:"●  在你的电脑上运行";font.pixelSize:12;color:Theme.green} AppText {text:"免费软件，供学习交流与使用\nOUNet · 独立开发";font.pixelSize:11;color:Theme.muted}}
            }
        }
        Rectangle {visible:root.railNav;Layout.fillHeight:true;width:1;color:Theme.border}
        ColumnLayout {
            Layout.fillWidth:true;Layout.fillHeight:true;spacing:0
            Rectangle {
                Layout.fillWidth:true;implicitHeight:52;color:Theme.surface
                RowLayout {
                    anchors.fill:parent;anchors.leftMargin:24;anchors.rightMargin:24;spacing:10
                    AppButton {visible:!root.railNav;text:"菜单";quiet:true;Accessible.name:"打开导航";onClicked:navMenu.open()}
                    Icon {name:v.networkKind==="Ethernet"?"network":"wifi";width:18;height:18}
                    AppText {text:v.wifi;Layout.fillWidth:true;font.pixelSize:12;color:Theme.ink;maximumLineCount:2;elide:Text.ElideRight}
                    AppButton {text:"网络设置 ↗";quiet:true;onClicked:Backend.openLocation("network")}
                }
                Rectangle {anchors.bottom:parent.bottom;height:1;width:parent.width;color:Theme.border}
            }
            ScrollView {
                id:mainScroll;Layout.fillWidth:true;Layout.fillHeight:true;clip:true
                ScrollBar.horizontal.policy:ScrollBar.AlwaysOff
                ColumnLayout {
                    width:mainScroll.availableWidth;spacing:18
                    ColumnLayout {
                        id:heading;Layout.fillWidth:true;Layout.leftMargin:28;Layout.rightMargin:28;Layout.topMargin:26;Layout.bottomMargin:6;spacing:7
                        AppText {text:"YOUR CAMPUS, CONNECTED";font.pixelSize:10;font.letterSpacing:1.5;color:Theme.blue}
                        AppText {text:root.navigation.find(p=>p.key===root.page).label;font.pixelSize:28;font.bold:true;Layout.fillWidth:true}
                        AppText {text:root.page==="overview"?"连接状态、在线身份与恢复记录，清楚呈现。":root.page==="account"?"保存账号与密码，选择下次认证使用的身份。":root.page==="settings"?"让连接节奏和 Windows 使用习惯保持一致。":"遇到问题有线索，数据使用有说明。";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true}
                    }
                    Panel {
                        visible:v.blocked||!!v.credentialError||v.migrationPending||v.status==="noncampus"||v.status==="campusUnavailable"
                        Layout.fillWidth:true;Layout.leftMargin:28;Layout.rightMargin:28
                        color:v.blocked||v.migrationPending?Theme.amberBg:Theme.redBg;padding:16
                        RowLayout {
                            Layout.fillWidth:true;spacing:14
                            ColumnLayout {Layout.fillWidth:true;spacing:4;AppText {text:v.blocked?"自动网络请求已暂停":v.migrationPending?"旧入口待处理":v.credentialError||v.title;color:v.blocked||v.migrationPending?Theme.amber:Theme.red;font.bold:true;Layout.fillWidth:true} AppText {text:v.blocked?v.pauseMessage:v.migrationPending?"请核对原脚本入口，再由桌面版接管。":v.credentialError?"这组凭据的自动 XHA 请求已停止。外网可用不代表密码正确。":v.message;font.pixelSize:12;color:v.blocked||v.migrationPending?Theme.amber:Theme.red;Layout.fillWidth:true}}
                            AppButton {objectName:"retryRequestsButton";visible:v.blocked;text:"手动重试";enabled:!v.busy;onClicked:Backend.retryRequests()}
                            AppButton {visible:!v.blocked&&!v.migrationPending&&!!v.credentialError;text:"修改密码";enabled:!v.busy;onClicked:{accountsPage.load(v.activeAccountId);root.go("account")}}
                            AppButton {visible:v.migrationPending&&!v.blocked;text:"核对旧入口";enabled:!v.busy;onClicked:Backend.rescanLegacy()}
                        }
                    }
                    OverviewPage {visible:root.page==="overview";v:root.v;Layout.fillWidth:true;Layout.leftMargin:28;Layout.rightMargin:28;onDevicesRequested:root.openDetails("devices");onSettingsRequested:root.go("settings");onAccountsRequested:root.go("account")}
                    AccountsPage {id:accountsPage;objectName:"accountEditor";visible:root.page==="account";v:root.v;Layout.fillWidth:true;Layout.leftMargin:28;Layout.rightMargin:28}
                    SettingsPage {visible:root.page==="settings";v:root.v;Layout.fillWidth:true;Layout.leftMargin:28;Layout.rightMargin:28;onInfoRequested:key=>root.openDetails(key)}
                    HelpPage {visible:root.page==="help";v:root.v;Layout.fillWidth:true;Layout.leftMargin:28;Layout.rightMargin:28;onDetailsRequested:key=>root.openDetails(key)}
                    Item {height:12;Layout.fillWidth:true}
                }
            }
            Rectangle {
                Layout.fillWidth:true;implicitHeight:32;color:Theme.sub
                RowLayout {anchors.fill:parent;anchors.leftMargin:24;anchors.rightMargin:24;AppText {text:v.busy?"●  正在处理，请等待本轮完成":v.blocked?"●  自动请求已暂停":v.monitor?"●  自动监测运行中":"●  自动监测已关闭";font.pixelSize:11;color:v.blocked?Theme.amber:Theme.muted;Layout.fillWidth:true} AppText {visible:root.width>800;text:"本地保存 · HTTPS 认证";font.pixelSize:11;color:Theme.muted}}
            }
        }
    }
    Menu {id:navMenu;Repeater {model:root.navigation;MenuItem {required property var modelData;text:modelData.label;onTriggered:root.go(modelData.key)}}}
    Drawer {
        id:detailDrawer;parent:Overlay.overlay;edge:Qt.RightEdge;width:Math.min(root.detailKey==="diagnostics"?560:460,root.width);height:root.height;modal:true;focus:true;closePolicy:Popup.CloseOnEscape|Popup.CloseOnPressOutside
        background:Rectangle {color:Theme.surface;border.color:Theme.border}
        onClosed:{if(root.previousFocus)root.previousFocus.forceActiveFocus();root.detailKey=""}
        contentItem:ColumnLayout {
            spacing:16
            RowLayout {Layout.fillWidth:true;Layout.margins:20;ColumnLayout {Layout.fillWidth:true;AppText {text:root.detailKey==="devices"?"ONLINE DEVICES":root.detailKey==="diagnostics"?"DIAGNOSTICS":"OUNET · DETAILS";font.pixelSize:10;color:Theme.blue;Layout.fillWidth:true} AppText {text:root.detailTitle;font.pixelSize:23;font.bold:true;Layout.fillWidth:true}} AppButton {text:"×";quiet:true;Accessible.name:"关闭详情";onClicked:detailDrawer.close()}}
            RowLayout {visible:root.detailKey==="diagnostics";Layout.fillWidth:true;Layout.leftMargin:20;Layout.rightMargin:20;DropDown {model:["全部记录","XHA / 认证阶段","异常记录"];Layout.fillWidth:true;Accessible.name:"诊断筛选";onActivated:index=>root.logFilter=["all","xha","error"][index]} AppButton {text:root.newLogs?root.newLogs+" 条新记录":"回到最新";onClicked:root.refreshLogs(true)}}
            ScrollView {
                id:detailsScroll;Layout.fillWidth:true;Layout.fillHeight:true;clip:true;ScrollBar.horizontal.policy:ScrollBar.AlwaysOff
                ColumnLayout {
                    width:detailsScroll.availableWidth;spacing:16
                    AppText {visible:!!root.detailText;text:root.detailText;Layout.fillWidth:true;Layout.leftMargin:22;Layout.rightMargin:22}
                    AppText {visible:root.detailKey==="devices";text:root.v.busy?"正在读取在线设备…":root.v.devices.account?"XHA 在线账号："+root.v.devices.account+"\n读取于 "+String(root.v.devices.fetchedAt).replace("T"," ").slice(0,19):"尚未取得设备信息";font.pixelSize:12;color:Theme.muted;Layout.fillWidth:true;Layout.leftMargin:22;Layout.rightMargin:22}
                    Repeater {
                        model:root.detailKey==="devices"?(root.v.devices.items||[]):[]
                        Panel {required property var modelData;Layout.fillWidth:true;Layout.leftMargin:22;Layout.rightMargin:22;padding:16
                            AppText {text:(modelData.owner?"本机 · ":"在线设备 · ")+modelData.mobile;font.bold:true;Layout.fillWidth:true}
                            CopyText {text:"源 IPv4  "+modelData.ip+"\n上线时间  "+modelData.since+"\n源 IPv6  "+modelData.ipv6+"\n源 MAC  "+modelData.mac;Layout.fillWidth:true;font.pixelSize:13}
                        }
                    }
                    AppText {visible:root.detailKey==="devices";text:root.v.devices.message||(root.v.devices.items?"已读取 "+root.v.devices.items.length+" 条"+(root.v.devices.complete?"，本次读取完成。":"，尚未全部读取。")+"\n只读展示，不提供强制下线。":"");Layout.fillWidth:true;Layout.leftMargin:22;Layout.rightMargin:22;font.pixelSize:12;color:Theme.muted}
                    RowLayout {visible:root.detailKey==="devices";Layout.leftMargin:22;AppButton {text:"刷新设备";enabled:!root.v.busy&&!root.v.blocked;onClicked:Backend.loadDevices(false)} AppButton {visible:!!root.v.devices.next&&!root.v.devices.complete;text:"加载更多";enabled:!root.v.busy&&!root.v.blocked;onClicked:Backend.loadDevices(true)}}
                    Repeater {
                        model:root.detailKey==="diagnostics"?root.logEntries.filter(e=>root.logFilter==="all"||root.logFilter==="xha"&&(e.kind==="stage"||e.message.indexOf("XHA")>=0)||root.logFilter==="error"&&e.kind==="warning"):[]
                        ColumnLayout {required property var modelData;Layout.fillWidth:true;Layout.leftMargin:22;Layout.rightMargin:22;spacing:6;AppText {text:modelData.time+" · "+modelData.kind;font.pixelSize:12;color:Theme.muted} CopyText {text:modelData.message;Layout.fillWidth:true;font.pixelSize:13} Rectangle {Layout.fillWidth:true;height:1;color:Theme.border;Layout.topMargin:6}}
                    }
                    AppText {visible:root.detailKey==="diagnostics"&&!root.logEntries.length;text:"暂无本地诊断记录。";color:Theme.muted;Layout.leftMargin:22}
                    AppButton {visible:root.detailKey==="export";text:"选择位置并导出";enabled:!v.busy;Layout.leftMargin:22;onClicked:Backend.exportLogs()}
                    AppText {visible:root.detailKey==="update";text:root.v.updateInfo.message||"正在检查…";Layout.fillWidth:true;Layout.leftMargin:22;Layout.rightMargin:22}
                    CopyText {visible:root.detailKey==="update"&&!!root.v.updateInfo.sha256;text:"版本："+(root.v.updateInfo.version||"")+"\nSHA-256："+(root.v.updateInfo.sha256||"")+"\n"+(root.v.updateInfo.notes||"");font.pixelSize:12;Layout.fillWidth:true;Layout.leftMargin:22;Layout.rightMargin:22}
                    AppButton {visible:root.detailKey==="update"&&!!root.v.updateInfo.newer;text:"在浏览器下载安装包 ↗";enabled:!v.blocked;Layout.leftMargin:22;onClicked:Backend.openUpdate()}
                    AppText {visible:v.status==="error"||v.blocked;text:v.blocked?v.pauseMessage:v.message;color:Theme.amber;font.pixelSize:12;Layout.fillWidth:true;Layout.leftMargin:22;Layout.rightMargin:22}
                    Item {height:20;Layout.fillWidth:true}
                }
            }
        }
        Timer {interval:1000;repeat:true;running:detailDrawer.opened&&root.detailKey==="diagnostics"&&root.visible;onTriggered:root.refreshLogs(false)}
    }
    Rectangle {id:noticeBox;objectName:"noticeBox";visible:false;z:100;anchors.horizontalCenter:parent.horizontalCenter;anchors.bottom:parent.bottom;anchors.bottomMargin:44;width:Math.min(root.width-40,650);height:noticeLabel.implicitHeight+26;radius:10;color:Theme.ink;AppText {id:noticeLabel;anchors.fill:parent;anchors.margins:13;color:Theme.surface;font.pixelSize:13}}
    Timer {id:noticeTimer;interval:4500;onTriggered:noticeBox.visible=false}
    Dialog {id:quitDialog;anchors.centerIn:Overlay.overlay;modal:true;title:"退出 OUNet";width:360;contentItem:ColumnLayout {spacing:18;AppText {text:"退出后停止网络监测。"} RowLayout {AppButton {text:"退出";onClicked:{quitDialog.close();Backend.quit()}} AppButton {text:"取消";onClicked:quitDialog.close()}}}}
    Dialog {
        id:migrationDialog;anchors.centerIn:Overlay.overlay;modal:true;title:"核对旧脚本入口";width:Math.min(root.width-40,580)
        contentItem:ColumnLayout {spacing:14;CopyText {text:"原目录："+v.legacyDirectory;Layout.fillWidth:true;font.pixelSize:12} Repeater {model:v.tasks;AppText {required property var modelData;text:modelData.name+" · "+(modelData.enabled?"已启用":"已停用");Layout.fillWidth:true}} AppText {text:"只处理重新核对后精确匹配的项目任务。已有请求暂停继续保留。";Layout.fillWidth:true;font.pixelSize:12;color:Theme.muted} AppButton {text:"停用匹配任务并完成迁移";enabled:!v.busy;onClicked:Backend.completeMigration(true)} AppButton {text:"已手动停用，重新核对";enabled:!v.busy;onClicked:Backend.completeMigration(false)} AppButton {text:"稍后处理";onClicked:migrationDialog.close()}}
        Connections {target:Backend;function onMigrationCompleted(){migrationDialog.close()}}
    }
}
