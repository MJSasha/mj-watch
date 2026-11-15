plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
 namespace = "dev.mjwatch.tv"
 compileSdk = 36
 defaultConfig { minSdk = 26; applicationId = "dev.mjwatch.tv"; targetSdk = 36; versionCode = providers.gradleProperty("mjwatchVersionCode").get().toInt(); versionName = providers.gradleProperty("mjwatchVersionName").get(); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true }
 buildTypes { getByName("release") { isMinifyEnabled = false } }
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
 implementation(project(":design-system"))
 androidTestImplementation(platform(libs.compose.bom))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test:rules:1.7.0")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
