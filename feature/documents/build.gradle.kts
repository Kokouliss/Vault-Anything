plugins {
    alias(libs.plugins.app.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kokouliss.vaultanything.feature.documents"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.print)
    implementation(libs.kotlinx.serialization)

    testImplementation(libs.bundles.unit.test)
}
