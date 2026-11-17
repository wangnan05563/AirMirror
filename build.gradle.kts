plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// All reproducible Gradle output is collected under release/:
//   release/build/<module>  module build directories
//   release/.gradle         project cache (see gradle.properties)
//   release/kotlin          Kotlin plugin persistent dir (see gradle.properties)
// release/ is the single disposable artifact root, so deleting it and rebuilding
// always reproduces the full set. Non-artifact state (patch sequence) lives in
// .build-state/ and is deliberately kept outside release/.
allprojects {
    // Kotlin 2.0's persistent-dir property must be absolute on Windows; a
    // relative value makes the compiler look for a session path it cannot create.
    extensions.extraProperties["kotlin.project.persistent.dir"] =
        rootProject.layout.projectDirectory.dir("release/kotlin").asFile.absolutePath
    val moduleDir = if (path == ":") "root" else path.removePrefix(":").replace(':', '-')
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("release/build/$moduleDir").asFile)
}
