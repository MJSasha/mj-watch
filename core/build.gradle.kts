plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.ksp) }
android {
 namespace = "dev.mjwatch.core"
 compileSdk = 36
 defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 api("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("com.github.mwiede:jsch:0.2.26")
 api("androidx.room:room-runtime:2.8.4")
 implementation("androidx.room:room-ktx:2.8.4")
 ksp("androidx.room:room-compiler:2.8.4")
 api("androidx.datastore:datastore-preferences:1.2.0")
 androidTestImplementation("androidx.test:runner:1.7.0")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
 androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.16.1")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
