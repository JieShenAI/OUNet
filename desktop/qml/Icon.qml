import QtQuick
Item {
    property string name: "wifi"
    property string tone: "blue"
    implicitWidth: 20; implicitHeight: 20
    width: 20; height: 20
    Image {
        anchors.fill: parent
        source: parent.name ? "../assets/icons/" + parent.name + "-" + (Theme.dark&&parent.tone==="blue"?"white":parent.tone) + ".svg" : ""
        sourceSize.width: 64; sourceSize.height: 64
        fillMode: Image.PreserveAspectFit
        smooth: true
    }
}
