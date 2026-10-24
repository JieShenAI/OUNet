import QtQuick
// Keep the parent control enabled and visually stable while the serial worker
// is occupied. Consume pointer input without toggling hover, focus or palette.
MouseArea {
    property bool busy: false
    anchors.fill: parent
    z: 100
    enabled: busy
    acceptedButtons: Qt.AllButtons
    preventStealing: true
    function blockKey(event) {
        if (busy && event.key !== Qt.Key_Tab && event.key !== Qt.Key_Backtab && event.key !== Qt.Key_Escape)
            event.accepted = true
    }
    onWheel: event => event.accepted = true
}
