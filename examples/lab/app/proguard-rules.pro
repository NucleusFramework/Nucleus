# Nucleus Lab release rules, on top of the plugin's defaults and the libraries' own
# consumer rules (`consumerRules = true`). Metro generates the graph, so nothing is
# reached by reflection on the Lab side.

# The additional `nucleus-lab-cli` launcher's entry point.
-keep class dev.nucleusframework.lab.app.LabCliKt {
    public static void main(java.lang.String[]);
}

# Jewel: sealed painter hints (ICCE when the hierarchy is rewritten) and the
# commonmark/autolink enums its Markdown renderer resolves at runtime.
-keepattributes PermittedSubclasses
-keep class org.jetbrains.jewel.ui.painter.** { *; }
-keep class org.nibor.autolink.** { *; }
-keep enum org.nibor.autolink.** { *; }
-keep class org.commonmark.ext.autolink.** { *; }

# Optional platform integrations several libraries probe for (Conscrypt, SLF4J, JBR…).
-dontwarn **
