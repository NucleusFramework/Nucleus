import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property

open class GenerateBuildConfig : DefaultTask() {
    @get:Input
    val fieldsToGenerate: MapProperty<String, Any> = project.objects.mapProperty()

    @get:Input
    val classFqName: Property<String> = project.objects.property()

    @get:OutputDirectory
    val generatedOutputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun execute() {
        val dir = generatedOutputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()

        val fqName = classFqName.get()
        val parts = fqName.split(".")
        val className = parts.last()
        val file = dir.resolve("$className.kt")
        val content =
            buildString {
                if (parts.size > 1) {
                    appendLine("package ${parts.dropLast(1).joinToString(".")}")
                }

                appendLine()
                appendLine("/* GENERATED, DO NOT EDIT MANUALLY! */")
                appendLine("object $className {")
                for ((key, value) in fieldsToGenerate.get().entries.sortedBy { it.key }) {
                    appendLine("const val $key = ${if (value is String) "\"$value\"" else value.toString()}")
                }
                appendLine("}")
            }
        file.writeText(content)
    }
}

val buildConfigDir
    get() = project.layout.buildDirectory.dir("generated/buildconfig")
val composeVersion = project.findProperty("compose.version")?.toString() ?: "1.10.0"
val composeMaterial3Version = project.findProperty("compose.material3.version")?.toString() ?: "1.9.0"
val buildConfig =
    tasks.register("buildConfig", GenerateBuildConfig::class.java) {
        classFqName.set("dev.nucleusframework.NucleusBuildConfig")
        generatedOutputDir.set(buildConfigDir)
        fieldsToGenerate.put("composeVersion", composeVersion)
        fieldsToGenerate.put("composeMaterial3Version", composeMaterial3Version)
        fieldsToGenerate.put("composeGradlePluginVersion", composeVersion)
    }

tasks.named("compileKotlin") {
    dependsOn(buildConfig)
}

extensions.configure<SourceSetContainer>("sourceSets") {
    named("main") {
        java.srcDir(buildConfig.flatMap { it.generatedOutputDir })
    }
}
