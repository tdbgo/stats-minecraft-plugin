$ErrorActionPreference = "Stop"

$gradleWrapper = Join-Path $PSScriptRoot "..\gradlew.bat"
if (-not (Test-Path $gradleWrapper)) {
  throw "Gradle wrapper is missing: $gradleWrapper"
}

Write-Host "Running Gradle 9.6.1 / Java 25 build..."
& $gradleWrapper --no-daemon clean build --console=plain
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}
