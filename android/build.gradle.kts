plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.spotless)
}

spotless {
  kotlin {
    target("app/src/**/*.kt")
    ktlint(libs.versions.ktlint.get()).setEditorConfigPath(rootProject.file("../.editorconfig"))
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlinGradle {
    target("*.gradle.kts", "app/*.gradle.kts")
    ktlint(libs.versions.ktlint.get()).setEditorConfigPath(rootProject.file("../.editorconfig"))
    trimTrailingWhitespace()
    endWithNewline()
  }
  format("misc") {
    target("gradle.properties", "app/src/**/*.xml")
    trimTrailingWhitespace()
    endWithNewline()
  }
}
