pragma Singleton
import QtQuick
QtObject {
    readonly property bool highContrast: Backend.data.systemHighContrast
    property SystemPalette systemPalette: SystemPalette {colorGroup:SystemPalette.Active}
    readonly property bool dark: Backend.data.appearance === "dark" || (Backend.data.appearance === "system" && Qt.styleHints.colorScheme === Qt.Dark)
    readonly property color bg: highContrast ? systemPalette.window : dark ? "#101925" : "#f4f7fb"
    readonly property color surface: highContrast ? systemPalette.window : dark ? "#182434" : "#ffffff"
    readonly property color sub: highContrast ? systemPalette.window : dark ? "#14202e" : "#f8fafd"
    readonly property color ink: highContrast ? systemPalette.windowText : dark ? "#edf3fc" : "#172d46"
    readonly property color muted: highContrast ? systemPalette.windowText : dark ? "#b0c0d2" : "#52677e"
    readonly property color border: highContrast ? systemPalette.windowText : dark ? "#364a60" : "#dfe7f1"
    readonly property color blue: highContrast ? systemPalette.highlight : dark ? "#80b9ff" : "#004098"
    readonly property color selected: highContrast ? systemPalette.alternateBase : dark ? "#253d5b" : "#e8f0fc"
    readonly property color primaryText: highContrast ? systemPalette.highlightedText : dark ? "#10233c" : "#ffffff"
    readonly property color green: highContrast ? systemPalette.windowText : dark ? "#7ddbbd" : "#087a60"
    readonly property color greenBg: highContrast ? systemPalette.window : dark ? "#173c34" : "#e9f6f0"
    readonly property color red: highContrast ? systemPalette.windowText : dark ? "#ffa9b0" : "#af2735"
    readonly property color redBg: highContrast ? systemPalette.window : dark ? "#42252e" : "#fff0f1"
    readonly property color amber: highContrast ? systemPalette.windowText : dark ? "#f6d080" : "#845300"
    readonly property color amberBg: highContrast ? systemPalette.window : dark ? "#42331a" : "#fff5de"
}
