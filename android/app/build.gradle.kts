plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.megane14916.lifetimeline"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.megane14916.lifetimeline"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    abortOnError = true
    warningsAsErrors = true
    // Toolchain and SDK versions are reviewed and pinned together in docs/development/toolchains.md.
    disable +=
      setOf("AndroidGradlePluginVersion", "GradleDependency", "ObsoleteSdkInt", "OldTargetApi")
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui.tooling.preview)

  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
}
