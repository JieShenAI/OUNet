import QtQuick
import QtQuick.Controls.Basic
CheckBox {
    id: control
    property bool busy: false
    Keys.onPressed: event => inputGuard.blockKey(event)
    BusyBlocker { id: inputGuard; busy: control.busy }
}
