// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// Toolchain versions are pinned to what compileSdk 36 needs, and every one of
// them was checked to exist before being written here:
//   AGP 8.13.1        - Google Maven, supports compileSdk 36; AGP 8.7.3 only
//                       knows up to 35 and would reject 36 at configure time
//   Kotlin 2.1.21     - required by AGP 8.13's Kotlin support range
//   Gradle 8.13       - AGP 8.13's minimum (see gradle-wrapper.properties)
// Kotlin 2.x also enables the built-in Compose compiler, which this project
// does not use (traditional Views only), so nothing else changes.
plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}
