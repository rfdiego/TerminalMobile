#Requires -Version 5.1
# TerminalMobile - APK Builder
# Downloads JDK 17, Android SDK and Gradle automatically.
# No Android Studio needed. Everything cached in .build-tools\

# Stop only on cmdlet errors; native EXEs (java, gradle) write to stderr
# normally, so we handle those with explicit exit-code checks.
$ErrorActionPreference = "Stop"

# Silence progress bars for Invoke-WebRequest (massive speed-up)
$ProgressPreference = "SilentlyContinue"

# Helper: run a native exe and capture stdout+stderr without triggering Stop
function Invoke-Native {
    param([string]$Exe, [string[]]$Args)
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & $Exe @Args 2>&1
    $ErrorActionPreference = $old
    return $output
}

# --- Paths -------------------------------------------------------------------
$SCRIPT_DIR  = Split-Path -Parent $MyInvocation.MyCommand.Path
$TOOLS_DIR   = "$SCRIPT_DIR\.build-tools"
$JDK_DIR     = "$TOOLS_DIR\jdk17"
$SDK_DIR     = "$TOOLS_DIR\android-sdk"
$GRADLE_DIR  = "$TOOLS_DIR\gradle-8.4"
$ANDROID_DIR = "$SCRIPT_DIR\android"
$DESKTOP     = [System.Environment]::GetFolderPath("Desktop")
$OUTPUT_APK  = "$ANDROID_DIR\app\build\outputs\apk\debug\app-debug.apk"
$DEST_APK    = "$DESKTOP\TerminalMobile.apk"

# --- Helpers -----------------------------------------------------------------
function step($n, $msg) { Write-Host "" ; Write-Host "[$n/5] $msg" -ForegroundColor Cyan }
function ok($msg)       { Write-Host "  OK  $msg" -ForegroundColor Green }
function info($msg)     { Write-Host "      $msg" -ForegroundColor DarkGray }
function warn($msg)     { Write-Host "  !!  $msg" -ForegroundColor Yellow }
function die($msg)      { Write-Host "" ; Write-Host "FAIL $msg" -ForegroundColor Red ; exit 1 }

function Get-BigFile($url, $dest, $label) {
    info "Downloading $label ..."
    try {
        Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -TimeoutSec 600
        ok "Downloaded: $(Split-Path $dest -Leaf)"
    } catch {
        die "Download failed: $label`n  URL: $url`n  $_"
    }
}

function Unzip($zip, $dest) {
    info "Extracting $(Split-Path $zip -Leaf) ..."
    Expand-Archive -Path $zip -DestinationPath $dest -Force
}

# --- Banner ------------------------------------------------------------------
Clear-Host
Write-Host ""
Write-Host "  TerminalMobile - APK Builder" -ForegroundColor Green
Write-Host "  -------------------------------------------------------"
Write-Host "  No Android Studio needed." -ForegroundColor White
Write-Host "  First run downloads ~700 MB (JDK + SDK + Gradle)." -ForegroundColor Yellow
Write-Host "  Subsequent builds use cache and take ~30 seconds." -ForegroundColor White
Write-Host "  -------------------------------------------------------"
Write-Host ""

New-Item -ItemType Directory -Force -Path $TOOLS_DIR | Out-Null

# =============================================================================
# STEP 1 - JDK 17
# =============================================================================
step 1 "Java Development Kit 17"

$javaExe = "$JDK_DIR\bin\java.exe"
if (Test-Path $javaExe) {
    ok "Already cached"
} else {
    $jdkZip = "$TOOLS_DIR\jdk17.zip"
    Get-BigFile `
        "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" `
        $jdkZip `
        "JDK 17 (~180 MB)"

    $tmp = "$TOOLS_DIR\jdk17-tmp"
    Unzip $jdkZip $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) { die "Unexpected JDK archive layout" }
    Move-Item $inner.FullName $JDK_DIR
    Remove-Item $jdkZip -Force
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    ok "JDK 17 ready"
}

$env:JAVA_HOME = $JDK_DIR
$env:PATH      = "$JDK_DIR\bin;$env:PATH"
# java -version writes to stderr; use Invoke-Native to avoid Stop behavior
$jv = (Invoke-Native $javaExe @("-version")) | Select-Object -First 1
info "$jv"

# =============================================================================
# STEP 2 - Android SDK Command-Line Tools
# =============================================================================
step 2 "Android SDK Command-Line Tools"

$cmdlatest  = "$SDK_DIR\cmdline-tools\latest"
$sdkmanager = "$cmdlatest\bin\sdkmanager.bat"

if (Test-Path $sdkmanager) {
    ok "Already cached"
} else {
    New-Item -ItemType Directory -Force -Path "$SDK_DIR\cmdline-tools" | Out-Null
    $cmdZip = "$TOOLS_DIR\cmdline-tools.zip"

    $downloaded = $false
    foreach ($url in @(
        "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip",
        "https://dl.google.com/android/repository/commandlinetools-win-10406996_latest.zip"
    )) {
        try {
            Get-BigFile $url $cmdZip "Android cmdline-tools (~116 MB)"
            $downloaded = $true
            break
        } catch {
            warn "URL failed, trying fallback..."
            if (Test-Path $cmdZip) { Remove-Item $cmdZip -Force }
        }
    }
    if (-not $downloaded) { die "Could not download Android cmdline-tools" }

    $tmp = "$TOOLS_DIR\cmdline-tools-tmp"
    Unzip $cmdZip $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) { die "Unexpected cmdline-tools archive layout" }
    Move-Item $inner.FullName $cmdlatest
    Remove-Item $cmdZip -Force
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    ok "cmdline-tools ready"
}

$env:ANDROID_HOME = $SDK_DIR
$env:PATH         = "$cmdlatest\bin;$SDK_DIR\platform-tools;$env:PATH"

# =============================================================================
# STEP 3 - Android Platform 34 + Build Tools 34.0.0
# =============================================================================
step 3 "Android Platform 34 + Build Tools 34.0.0"

# Pre-accept licenses by writing standard Google license hashes
$licDir = "$SDK_DIR\licenses"
New-Item -ItemType Directory -Force -Path $licDir | Out-Null
Set-Content "$licDir\android-sdk-license" `
    "24333f8a63b6825ea9c5514f83c2829b004d1fee`n8933bad161af4178b1185d1a37fbf41ea5269c55`nd56f5187479451eabf01fb78af6dfcb131a6481e"
Set-Content "$licDir\android-sdk-preview-license" "84831b9409646a918e30573bab4c9c91346d8abd"
Set-Content "$licDir\google-gdk-license"          "33b6a2b64607f11b759f320ef9dff4ae5c47d97a"

foreach ($pkg in @(
    @{ id = "platforms;android-34"; check = "platforms\android-34" },
    @{ id = "build-tools;34.0.0";   check = "build-tools\34.0.0"   },
    @{ id = "platform-tools";       check = "platform-tools"        }
)) {
    if (Test-Path "$SDK_DIR\$($pkg.check)") {
        ok "Already installed: $($pkg.id)"
    } else {
        info "Installing $($pkg.id) ..."
        # sdkmanager also writes to stderr; use Invoke-Native
        $out = Invoke-Native "$sdkmanager" @("--sdk_root=$SDK_DIR", $pkg.id)
        if ($LASTEXITCODE -ne 0) {
            warn "sdkmanager non-zero exit for $($pkg.id) (may still work)"
        } else {
            ok "Installed: $($pkg.id)"
        }
    }
}

# =============================================================================
# STEP 4 - Gradle 8.4
# =============================================================================
step 4 "Gradle 8.4"

$gradleBin = "$GRADLE_DIR\bin\gradle.bat"
if (Test-Path $gradleBin) {
    ok "Already cached"
} else {
    $gradleZip = "$TOOLS_DIR\gradle-8.4-bin.zip"
    Get-BigFile `
        "https://services.gradle.org/distributions/gradle-8.4-bin.zip" `
        $gradleZip `
        "Gradle 8.4 (~150 MB)"

    $tmp = "$TOOLS_DIR\gradle-tmp"
    Unzip $gradleZip $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) { die "Unexpected Gradle archive layout" }
    Move-Item $inner.FullName $GRADLE_DIR
    Remove-Item $gradleZip -Force
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    ok "Gradle 8.4 ready"
}

$env:PATH = "$GRADLE_DIR\bin;$env:PATH"
$gv = (Invoke-Native "$gradleBin" @("--version")) | Select-String "Gradle \d" | Select-Object -First 1
info "$gv"

# =============================================================================
# STEP 5 - Build APK
# =============================================================================
step 5 "Gradle build -- assembleDebug"
info "Compiling Kotlin code and packaging the APK..."
info "(First build also downloads Kotlin compiler, ~80 MB)"
Write-Host ""

# Write local.properties so Gradle always finds the Android SDK
$sdkPath = $SDK_DIR.Replace("\", "\\")
Set-Content "$ANDROID_DIR\local.properties" "sdk.dir=$sdkPath"
info "local.properties -> sdk.dir=$SDK_DIR"

Push-Location $ANDROID_DIR
try {
    $env:GRADLE_OPTS = "-Xmx2g -Dfile.encoding=UTF-8"

    # Gradle writes build output to stdout; real errors go to stdout too
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"

    & "$gradleBin" assembleDebug --no-daemon 2>&1 | ForEach-Object {
        $line = "$_"
        if     ($line -match "FAILED|^e: |error:")   { Write-Host "  $line" -ForegroundColor Red    }
        elseif ($line -match "BUILD SUCCESSFUL")      { Write-Host "  $line" -ForegroundColor Green  }
        elseif ($line -match "^> Task :")             { Write-Host "  $line" -ForegroundColor White  }
        elseif ($line -match "warning:|Warning:")     { Write-Host "  $line" -ForegroundColor Yellow }
        else                                          { Write-Host "  $line" -ForegroundColor DarkGray }
    }

    $ErrorActionPreference = $old
    if ($LASTEXITCODE -ne 0) { die "Gradle exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}

# =============================================================================
# DONE
# =============================================================================
if (-not (Test-Path $OUTPUT_APK)) {
    die "APK not found at expected path:`n  $OUTPUT_APK"
}

Copy-Item $OUTPUT_APK $DEST_APK -Force
$size = [math]::Round((Get-Item $DEST_APK).Length / 1MB, 2)

Write-Host ""
Write-Host "  ======================================================" -ForegroundColor Green
Write-Host "  BUILD SUCCESSFUL" -ForegroundColor Green
Write-Host "  ======================================================" -ForegroundColor Green
Write-Host "  APK : $DEST_APK" -ForegroundColor White
Write-Host "  Size: ${size} MB" -ForegroundColor White
Write-Host ""
Write-Host "  How to install on your Android phone:" -ForegroundColor Cyan
Write-Host "  1. Transfer TerminalMobile.apk  (USB, Drive, WhatsApp)"
Write-Host "  2. Settings > Security > Install unknown apps > Enable"
Write-Host "  3. Tap the APK file to install"
Write-Host "  ======================================================" -ForegroundColor Green
Write-Host ""

Start-Process explorer.exe -ArgumentList "/select,`"$DEST_APK`""
