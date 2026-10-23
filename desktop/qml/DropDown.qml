import QtQuick
import QtQuick.Controls.Basic
ComboBox {
    id: control
    implicitWidth: 190; implicitHeight: 36
    font.pixelSize: 13
    leftPadding: 11; rightPadding: 31
    background: Rectangle { radius: 6; color: control.enabled ? Theme.sub : Theme.sub; border.color: control.activeFocus ? Theme.blue : Theme.border }
    contentItem: Text { textFormat: Text.PlainText; text: control.displayText; font: control.font; color: Theme.ink; elide: Text.ElideRight; verticalAlignment: Text.AlignVCenter }
    indicator: Icon { name: "arrow"; tone: "muted"; width: 13; height: 13; rotation: 90; x: control.width - 24; y: (control.height - height) / 2 }
    delegate: ItemDelegate {
        required property var modelData
        required property int index
        width: control.width; height: 35
        text: String(modelData)
        font: control.font
        highlighted: control.highlightedIndex === index
        contentItem: Text { textFormat: Text.PlainText; text: parent.text; font: control.font; color: Theme.ink; elide: Text.ElideRight; verticalAlignment: Text.AlignVCenter }
        background: Rectangle { radius: 4; color: parent.highlighted ? Theme.selected : Theme.surface }
    }
    popup: Popup {
        y: control.height + 4
        width: control.width; implicitHeight: Math.min(contentItem.implicitHeight + 10, 280)
        padding: 5
        background: Rectangle { color: Theme.surface; radius: 7; border.color: Theme.border }
        contentItem: ListView { clip: true; implicitHeight: contentHeight; model: control.popup.visible ? control.delegateModel : null; currentIndex: control.highlightedIndex; ScrollIndicator.vertical: ScrollIndicator {} }
    }
}
