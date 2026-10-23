import QtQuick
import QtQuick.Controls.Basic
Switch {
    id: control
    implicitWidth: 38; implicitHeight: 24
    indicator: Rectangle {
        x: 1; y: 2; width: 36; height: 20; radius: 10
        color: control.checked ? Theme.blue : Theme.muted
        border.width: control.activeFocus ? 2 : 0
        border.color: Theme.blue
        Rectangle { width: 14; height: 14; radius: 7; y: 3; x: control.checked ? 19 : 3; color: Theme.primaryText }
    }
}
