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
        NucleusWidget(kind: "DemoTasks", displayName: "Tasks", description: "Rows, icons, a filter and a counter", families: [.systemMedium, .systemLarge])
        NucleusWidget(kind: "DemoDashboard", displayName: "CI dashboard", description: "Weighted tiles on colored backgrounds", families: [.systemMedium, .systemLarge])
        NucleusWidget(kind: "DemoWeather", displayName: "Weather", description: "A forecast in columns and a city choice", families: [.systemSmall, .systemMedium, .systemLarge])
    }
}
