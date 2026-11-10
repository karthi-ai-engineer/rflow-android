pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rflow-android"
// app: the screens. core: settings, the key, versions. ai: Gemini. (speech, keyboard, screen come with their phases.)
include(":app", ":core", ":ai")
