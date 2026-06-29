pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
    plugins {
        // Use property() or extra lookup to access gradle.properties values
        id(extra["quarkusPluginId"] as String) version (extra["quarkusPluginVersion"] as String)
    }
}

rootProject.name = "hr-management-backend"
