plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
 namespace = "dev.mjwatch.designsystem"
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
 api(project(":playback"))
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
 implementation("androidx.tv:tv-material:1.0.1")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("io.coil-kt:coil-svg:2.7.0")
}
