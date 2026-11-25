plugins {
    alias(libs.plugins.app.android.library)
    alias(libs.plugins.app.android.library.compose)
}

android {
    namespace = "com.kokouliss.vaultanything.core.ui"
}

dependencies {
    api(project(":core:domain"))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core)
}
