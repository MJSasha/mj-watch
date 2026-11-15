plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
 namespace = "dev.mjwatch.playback"
 compileSdk = 36
 defaultConfig { minSdk = 26; }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation(platform(libs.compose.bom))
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.activity:activity-compose:1.12.2")
 api(project(":core"))
 api(libs.media3.exoplayer)
 implementation(libs.media3.ui)
 implementation(libs.media3.session)
 implementation("androidx.media3:media3-datasource-okhttp:1.9.2")
}
