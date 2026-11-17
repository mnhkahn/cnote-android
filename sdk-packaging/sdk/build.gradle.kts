plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.cyeam.cnote.sdk"
    compileSdk = 35
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/assets"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs"))
    buildTypes { release { isMinifyEnabled = false } }
}
val verifyNativeLibraries by tasks.registering {
    doLast {
        val libs = layout.buildDirectory.dir("generated/jniLibs").get().asFile.walkTopDown().filter { it.name == "libcnote_core.so" }.toList()
        check(libs.isNotEmpty()) { "Build native core first: python3 scripts/build-android-sdk.py" }
    }
}
tasks.named("preBuild") { dependsOn(verifyNativeLibraries) }
