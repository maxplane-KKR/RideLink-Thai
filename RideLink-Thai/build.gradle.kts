import java.io.File

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir")
val buildRoot = File(localAppData, "ridelink-build")

allprojects {
    layout.buildDirectory.set(File(buildRoot, project.name))
}
