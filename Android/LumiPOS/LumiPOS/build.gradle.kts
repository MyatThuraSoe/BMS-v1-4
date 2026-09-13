plugins {
    id("com.android.application") version AppPlugins.androidApplication apply false
    id("com.android.library") version AppPlugins.androidLibrary apply false
    id("com.google.dagger.hilt.android") version AppPlugins.hilt apply false
    id("org.jetbrains.kotlin.android") version AppPlugins.kotlin apply false
    id("org.jetbrains.kotlin.kapt") version AppPlugins.kotlin apply false
    id("org.jetbrains.kotlin.plugin.compose") version AppPlugins.kotlin apply false
    id("org.jetbrains.kotlin.plugin.serialization") version AppPlugins.kotlin apply false
    id("com.google.devtools.ksp") version AppPlugins.ksp apply false
}

tasks.create<Delete>("clean") {
    delete {
        rootProject.layout.buildDirectory
    }
}
