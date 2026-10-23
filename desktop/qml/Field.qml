import QtQuick
import QtQuick.Controls.Basic
TextField {
    id: control
    implicitHeight: 38
    font.pixelSize: 14
    color: Theme.ink
    placeholderTextColor: Theme.muted
    leftPadding: 11; rightPadding: 11
    selectByMouse: true
    background: Rectangle { radius: 8; color: Theme.sub; border.width: control.activeFocus ? 2 : 1; border.color: control.activeFocus ? Theme.blue : Theme.border }
}
