import QtQuick
TextEdit {
    textFormat: TextEdit.PlainText
    color: Theme.ink
    font.pixelSize: 14
    readOnly: true
    selectByMouse: true
    wrapMode: TextEdit.WrapAnywhere
    selectionColor: Theme.selected
    selectedTextColor: Theme.ink
    Accessible.role: Accessible.StaticText
    Accessible.name: text
}
