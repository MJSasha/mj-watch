plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
 namespace = "dev.mjwatch.playback"
 compileSdk = 36
 defaultConfig { minSdk = 26; targetSdk = 36; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation(platform(libs.compose.bom))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")

 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 implementation(platform(libs.compose.bom))
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.activity:activity-compose:1.12.2")
 implementation("io.coil-kt:coil-compose:2.7.0")
 api(project(":core"))
 api(libs.media3.exoplayer)
 implementation(libs.media3.ui)
 implementation(libs.media3.session)
 implementation(libs.media3.datasource.okhttp)
 implementation("io.put:media3-ffmpeg-decoder:1.11.1+1")
}
