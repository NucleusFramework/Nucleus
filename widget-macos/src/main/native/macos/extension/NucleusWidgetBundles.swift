// NucleusWidgetBundles.swift
//
// One WidgetBundle per widget count, and the entry point that picks the one matching the kinds
// the Info.plist declares: WidgetBundleBuilder only takes `if #available`, so the slots cannot be
// chosen inside a single bundle. An app declares up to 16 widgets: growing that means a slot,
// a bundle and a case more.

import SwiftUI
import WidgetKit

enum S0: Slot { static let index = 0 }
enum S1: Slot { static let index = 1 }
enum S2: Slot { static let index = 2 }
enum S3: Slot { static let index = 3 }
enum S4: Slot { static let index = 4 }
enum S5: Slot { static let index = 5 }
enum S6: Slot { static let index = 6 }
enum S7: Slot { static let index = 7 }
enum S8: Slot { static let index = 8 }
enum S9: Slot { static let index = 9 }
enum S10: Slot { static let index = 10 }
enum S11: Slot { static let index = 11 }
enum S12: Slot { static let index = 12 }
enum S13: Slot { static let index = 13 }
enum S14: Slot { static let index = 14 }
enum S15: Slot { static let index = 15 }

struct Bundle1: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
    }
}

struct Bundle2: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
    }
}

struct Bundle3: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
    }
}

struct Bundle4: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
    }
}

struct Bundle5: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
    }
}

struct Bundle6: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
    }
}

struct Bundle7: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
    }
}

struct Bundle8: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
    }
}

struct Bundle9: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
        NucleusWidget<S8>()
    }
}

struct Bundle10: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
        NucleusWidget<S8>()
        NucleusWidget<S9>()
    }
}

struct Bundle11: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
        NucleusWidget<S8>()
        NucleusWidget<S9>()
        NucleusWidget<S10>()
    }
}

struct Bundle12: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
        NucleusWidget<S8>()
        NucleusWidget<S9>()
        NucleusWidget<S10>()
        NucleusWidget<S11>()
    }
}

struct Bundle13: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
        NucleusWidget<S8>()
        NucleusWidget<S9>()
        NucleusWidget<S10>()
        NucleusWidget<S11>()
        NucleusWidget<S12>()
    }
}

struct Bundle14: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
        NucleusWidget<S8>()
        NucleusWidget<S9>()
        NucleusWidget<S10>()
        NucleusWidget<S11>()
        NucleusWidget<S12>()
        NucleusWidget<S13>()
    }
}

struct Bundle15: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
        NucleusWidget<S8>()
        NucleusWidget<S9>()
        NucleusWidget<S10>()
        NucleusWidget<S11>()
        NucleusWidget<S12>()
        NucleusWidget<S13>()
        NucleusWidget<S14>()
    }
}

struct Bundle16: WidgetBundle {
    var body: some Widget {
        NucleusWidget<S0>()
        NucleusWidget<S1>()
        NucleusWidget<S2>()
        NucleusWidget<S3>()
        NucleusWidget<S4>()
        NucleusWidget<S5>()
        NucleusWidget<S6>()
        NucleusWidget<S7>()
        NucleusWidget<S8>()
        NucleusWidget<S9>()
        NucleusWidget<S10>()
        NucleusWidget<S11>()
        NucleusWidget<S12>()
        NucleusWidget<S13>()
        NucleusWidget<S14>()
        NucleusWidget<S15>()
    }
}

/// The bundle count the Info.plist asks for. With none declared the extension still has to run:
/// it shows one widget, a placeholder for an app built without its widgets.
@main
enum NucleusWidgetsMain {
    @MainActor
    static func main() {
        switch Config.definitions.count {
        case 2: Bundle2.main()
        case 3: Bundle3.main()
        case 4: Bundle4.main()
        case 5: Bundle5.main()
        case 6: Bundle6.main()
        case 7: Bundle7.main()
        case 8: Bundle8.main()
        case 9: Bundle9.main()
        case 10: Bundle10.main()
        case 11: Bundle11.main()
        case 12: Bundle12.main()
        case 13: Bundle13.main()
        case 14: Bundle14.main()
        case 15: Bundle15.main()
        case 16: Bundle16.main()
        case 17...: Bundle16.main()
        default: Bundle1.main()
        }
    }
}
