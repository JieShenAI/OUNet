import QtQuick
import QtQuick.Controls.Basic
Button {
    id: control
    property bool primary: false
    property bool quiet: false
    property bool busy: false
    signal triggered()
    property string iconName: ""
    implicitHeight: 36
    implicitWidth: label.implicitWidth + (iconName ? 27 : 0) + 30
    padding: 12
    font.pixelSize: 13
    hoverEnabled: true
    Keys.onPressed: event => inputGuard.blockKey(event)
    onClicked: { if (!busy) triggered() }
    BusyBlocker { id: inputGuard; busy: control.busy }
    background: Rectangle {
        radius: 7
        color: !control.enabled ? Theme.sub : control.primary ? Theme.blue : control.quiet ? (control.hovered ? Theme.selected : "transparent") : (control.hovered ? Theme.selected : Theme.surface)
        border.width: control.activeFocus ? 2 : control.quiet ? 0 : 1
        border.color: control.activeFocus ? Theme.blue : control.primary ? Theme.blue : Theme.border
    }
    contentItem: Item {
        implicitWidth: label.implicitWidth + (control.iconName ? 27 : 0)
        implicitHeight: 20
        Icon { visible: control.iconName !== ""; name: control.iconName; tone: control.primary && control.enabled ? "white" : "blue"; width: 16; height: 16; anchors.verticalCenter: parent.verticalCenter }
        Text { textFormat: Text.PlainText; id: label; text: control.text; anchors.centerIn: parent; anchors.horizontalCenterOffset: control.iconName ? 10 : 0; color: !control.enabled ? Theme.muted : control.primary ? Theme.primaryText : Theme.blue; font: control.font }
    }
}
