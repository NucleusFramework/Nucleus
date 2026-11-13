// The widget-ios demo extension: the widgets compose in Kotlin, right here.

import SwiftUI
import WidgetIosDemo
import WidgetKit

@main
struct DemoWidgets: WidgetBundle {
    init() {
        DemoWidgetsKt.startDemoWidgets()
    }

    var body: some Widget {
        NucleusWidget(
            kind: "DemoCounter",
            displayName: "Demo counter",
            description: "A counter composed by Kotlin in the widget extension",
            families: [.systemSmall, .systemMedium]
        )
    }
}
