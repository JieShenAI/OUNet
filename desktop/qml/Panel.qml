import QtQuick
import QtQuick.Layouts
Rectangle {
    id: panel
    property int padding: 22
    default property alias contents: body.data
    implicitHeight: body.implicitHeight + padding * 2
    radius: 16
    color: Theme.surface
    border.color: Theme.border
    ColumnLayout {
        id: body
        x: panel.padding; y: panel.padding
        width: panel.width - panel.padding * 2
        spacing: 16
    }
}
