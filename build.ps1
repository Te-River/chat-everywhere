# build.ps1 - One-shot rebuild of chat-everywhere APK into ./release/ (lowercase).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
#
# Behavior:
#   1. Ensure JDK 21 (keeps existing JAVA_HOME if valid).
#   2. Run Gradle assembleDebug (with --dependency-verification=off, required
#      in the internal-network environment).
#   3. Copy app-arm64-v8a-debug.apk and app-universal-debug.apk into ./release/.
#   4. Delete the old uppercase Release/ directory (keep lowercase release/).
#
# All output is ASCII-only so Windows PowerShell 5.1 parses it safely under
# any file encoding.

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

# ---- 1. JDK ----
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = "D:\Java\jdk-21"
    if (Test-Path "$jdk\bin\java.exe") {
        $env:JAVA_HOME = $jdk
        Write-Host "[build] JAVA_HOME -> $jdk"
    } else {
        Write-Host "[build][ERROR] JDK 21 not found (check JAVA_HOME or D:\Java\jdk-21)"
        exit 1
    }
}

# ---- 2. Gradle assembleDebug ----
Write-Host "[build] Running Gradle :app:assembleDebug ..."
& .\gradlew.bat :app:assembleDebug --console=plain --dependency-verification=off
if ($LASTEXITCODE -ne 0) {
    Write-Host "[build][ERROR] Gradle build failed (exit=$LASTEXITCODE)"
    exit $LASTEXITCODE
}

# ---- 3. Output to release/ (lowercase) ----
$ReleaseDir = Join-Path $ProjectRoot "release"
$ApkDir = Join-Path $ProjectRoot "app\build\outputs\apk\debug"

if (-not (Test-Path $ReleaseDir)) {
    New-Item -ItemType Directory -Path $ReleaseDir | Out-Null
}

$apks = @(
    "app-arm64-v8a-debug.apk",
    "app-universal-debug.apk"
)
foreach ($apk in $apks) {
    $src = Join-Path $ApkDir $apk
    if (Test-Path $src) {
        Copy-Item -Path $src -Destination (Join-Path $ReleaseDir $apk) -Force
        Write-Host "[build] Output $apk -> release\"
    } else {
        Write-Host "[build][WARN] $apk not found (skipped)"
    }
}

# ---- 4. Delete old uppercase Release/ (case-sensitive: Windows paths are
# ----    case-insensitive, so enumerate the REAL directory name from the
# ----    filesystem and only remove an actual uppercase "Release" - never
# ----    the freshly created lowercase release/).
$oldItem = Get-ChildItem -Path $ProjectRoot -Directory -Force |
    Where-Object { $_.Name -ceq "Release" }
if ($oldItem) {
    Remove-Item -LiteralPath $oldItem.FullName -Recurse -Force
    Write-Host "[build] Removed old Release/ directory"
} else {
    Write-Host "[build] No old Release/ directory to remove (keeping lowercase release/)"
}

Write-Host "[build] Done: APK files are in .\release\"
exit 0
