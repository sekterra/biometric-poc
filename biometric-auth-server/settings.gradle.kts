pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "biometric-auth-server"
include("biometric-auth-lib", "biometric-auth-app")
