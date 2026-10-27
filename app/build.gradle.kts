import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.gms.google-services") }
val localSigning=Properties().apply { rootProject.file(".signing/release.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
fun signingValue(name:String)=System.getenv(name) ?: localSigning.getProperty(name)
val tag=providers.gradleProperty("releaseVersion").orNull?.removePrefix("v") ?: "0.1.1"
require(tag.matches(Regex("\\d+\\.\\d+\\.\\d+"))) { "releaseVersion must be a stable semantic version" }
val parts=tag.split('.').map(String::toInt)
require(parts[0] in 0..2000 && parts[1] in 0..999 && parts[2] in 0..999) { "Version components out of range" }
android {
    namespace="com.cyeam.cnote"
    compileSdk=35
    defaultConfig {
        applicationId="com.cyeam.cnote"; minSdk=26; targetSdk=35
        versionCode=parts[0]*1_000_000+parts[1]*1_000+parts[2]; versionName=tag
        buildConfigField("String","APP_UPDATE_URL","\"https://www.cyeam.com/api/apps/cnote/update\"")
        buildConfigField("String","APP_UPDATE_MIRROR","\"https://github.com/mnhkahn/cnote-android/releases/latest/download/update.json\"")
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if(signingValue("CNOTE_KEYSTORE")!=null) create("release") {
            storeFile=rootProject.file(signingValue("CNOTE_KEYSTORE")!!)
            storePassword=signingValue("CNOTE_STORE_PASSWORD"); keyAlias="cnote"; keyPassword=signingValue("CNOTE_STORE_PASSWORD")
        }
    }
    buildTypes {
        release { isMinifyEnabled=true; isShrinkResources=true; signingConfig=signingConfigs.findByName("release"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    buildFeatures { buildConfig=true }
    testOptions { animationsDisabled=true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-analytics")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
}
