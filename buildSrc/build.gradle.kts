plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("nucleusNativeModule") {
            id = "nucleus.native-module"
            implementationClass = "dev.nucleusframework.gradle.NativeModulePlugin"
        }
    }
}
