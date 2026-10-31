param(
    [string[]]$Tasks = @('assembleDebug'),
    [switch]$Offline,
    [string]$Sdk,
    [string]$Jdk,
    [string]$GradleHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

if (!$Jdk) {
    $jdkCandidates = @(
        $env:JAVA_HOME,
        'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot',
        'C:\Program Files\Java\jdk-17'
    ) + (Get-ChildItem -Directory 'C:\Program Files\Microsoft\jdk-17*' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    $Jdk = $jdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'release')) -and ((Get-Content -LiteralPath (Join-Path $_ 'release')) -match '^JAVA_VERSION="17[\."]') } | Select-Object -First 1
    if (!$Jdk) {
        $javac = Get-Command javac -ErrorAction SilentlyContinue
        if ($javac) {
            $Jdk = Split-Path -Parent (Split-Path -Parent $javac.Source)
        }
    }
}
$java = Join-Path $Jdk 'bin\java.exe'
$keytool = Join-Path $Jdk 'bin\keytool.exe'
$releaseFile = Join-Path $Jdk 'release'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $releaseFile) -or
    !((Get-Content -LiteralPath $releaseFile) -match '^JAVA_VERSION="17[\."]')) {
    throw 'ต้องใช้ JDK 17 ระบุพาธด้วย -Jdk หาก javac ใน PATH เป็นเวอร์ชันอื่น'
}
if (!$Sdk) {
    $sdkCandidates = @(
        'C:\Android\sdk',
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        'C:\Users\Theboy-AsusTUF\AppData\Local\Android\Sdk'
    )
    $Sdk = $sdkCandidates |
        Where-Object { !([string]::IsNullOrWhiteSpace($_)) -and (Test-Path -LiteralPath (Join-Path $_ 'platforms\android-34\android.jar')) } |
        Select-Object -First 1
}
if (!$Sdk -or !(Test-Path -LiteralPath (Join-Path $Sdk 'platforms\android-34\android.jar')) -or
    !(Test-Path -LiteralPath (Join-Path $Sdk 'build-tools\34.0.0\aapt2.exe'))) {
    throw 'ต้องมี Android SDK platform 34 และ build-tools 34.0.0 อยู่แล้ว สคริปต์นี้ไม่ติดตั้ง SDK เพิ่ม'
}

if (!$GradleHome) {
    $cachedDistributions = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.9-bin'
    $cachedGradle = Get-ChildItem -LiteralPath $cachedDistributions -Filter gradle.bat -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($cachedGradle) {
        $GradleHome = Split-Path -Parent (Split-Path -Parent $cachedGradle.FullName)
    }
}
$gradle = if ($GradleHome) { Join-Path $GradleHome 'bin\gradle.bat' } else { '' }
if (!$gradle -or !(Test-Path -LiteralPath $gradle) -or
    !(Test-Path -LiteralPath (Join-Path $GradleHome 'lib\gradle-launcher-8.9.jar'))) {
    throw 'ไม่พบ Gradle 8.9 ที่มีอยู่ ระบุพาธด้วย -GradleHome สคริปต์นี้ไม่ดาวน์โหลด Gradle เพิ่ม'
}

$gradleUserHome = Join-Path $env:LOCALAPPDATA 'ridelink-gradle-home'
$androidUserHome = Join-Path $env:LOCALAPPDATA 'ridelink-android-home'
$buildUserHome = Join-Path $env:LOCALAPPDATA 'ridelink-build-user'
$tempPath = Join-Path $env:LOCALAPPDATA 'ridelink-temp'
$signingPath = Join-Path $projectRoot 'signing'
New-Item -ItemType Directory -Force -Path $gradleUserHome,$androidUserHome,$buildUserHome,$tempPath,$signingPath | Out-Null

$environmentNames = @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','ANDROID_USER_HOME',
    'ANDROID_PREFS_ROOT','GRADLE_USER_HOME','GRADLE_RO_DEP_CACHE','JAVA_OPTS','TEMP','TMP',
    'RIDELINK_SIGNING_PASSWORD')
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

Push-Location -LiteralPath $projectRoot
try {
    $env:JAVA_HOME = $Jdk
    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    $env:ANDROID_USER_HOME = $androidUserHome
    Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
    $env:GRADLE_USER_HOME = $gradleUserHome
    $env:TEMP = $tempPath
    $env:TMP = $tempPath
    $env:JAVA_OPTS = "-Duser.home=`"$buildUserHome`" -Djava.io.tmpdir=`"$tempPath`""

    # ให้ Gradle อ่าน dependency เดิมโดยไม่ล็อกหรือเขียน cache นอก workspace
    $sharedCache = Join-Path $env:USERPROFILE '.gradle\caches'
    if (Test-Path -LiteralPath (Join-Path $sharedCache 'modules-2')) {
        $env:GRADLE_RO_DEP_CACHE = $sharedCache
    } else {
        Remove-Item Env:GRADLE_RO_DEP_CACHE -ErrorAction SilentlyContinue
    }

    $keystore = Join-Path $signingPath 'ridelink-local.jks'
    $signingProperties = Join-Path $signingPath 'local-signing.properties'
    if (!(Test-Path -LiteralPath $keystore)) {
        if (Test-Path -LiteralPath $signingProperties) {
            throw 'พบข้อมูลกุญแจเดิมแต่ไม่พบ keystore หยุดเพื่อไม่เปลี่ยนกุญแจลงนามโดยไม่ตั้งใจ'
        }
        $env:RIDELINK_SIGNING_PASSWORD = [guid]::NewGuid().ToString('N')
        & $keytool -genkeypair -keystore $keystore -alias ridelink-local -storetype JKS `
            -storepass:env RIDELINK_SIGNING_PASSWORD -keypass:env RIDELINK_SIGNING_PASSWORD `
            -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=RideLink Thai Local Build'
        if ($LASTEXITCODE -ne 0) { throw 'สร้างกุญแจลงนาม RideLinkไทย ไม่สำเร็จ' }
        @(
            'keyAlias=ridelink-local'
            "storePassword=$env:RIDELINK_SIGNING_PASSWORD"
            "keyPassword=$env:RIDELINK_SIGNING_PASSWORD"
        ) | Set-Content -LiteralPath $signingProperties -Encoding ascii
    } elseif (!(Test-Path -LiteralPath $signingProperties)) {
        throw 'พบ keystore แต่ไม่พบ local-signing.properties กรุณากู้ไฟล์เดิม ห้ามสร้างกุญแจทับ'
    }

    $gradleArguments = @('--daemon','--console=plain','--project-dir',$projectRoot,
        "-Duser.home=$buildUserHome","-Djava.io.tmpdir=$tempPath")
    if ($Offline) { $gradleArguments += '--offline' }
    $gradleArguments += $Tasks
    & $gradle @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle ไม่สำเร็จ (exit $LASTEXITCODE)" }

    if ($Tasks -contains 'assembleDebug') {
        $apk = Join-Path "$env:LOCALAPPDATA\ridelink-build\app" 'outputs\apk\debug\app-debug.apk'
        if (!(Test-Path -LiteralPath $apk)) {
            $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
        }
        if (Test-Path -LiteralPath $apk) {
            & (Join-Path $Sdk 'build-tools\34.0.0\apksigner.bat') verify $apk
            if ($LASTEXITCODE -ne 0) { throw 'ตรวจลายเซ็น APK ไม่ผ่าน' }
            $localApkDir = Join-Path $projectRoot 'app\build\outputs\apk\debug'
            New-Item -ItemType Directory -Force -Path $localApkDir | Out-Null
            $copiedApk = Join-Path $localApkDir 'app-debug.apk'
            Copy-Item -LiteralPath $apk -Destination $copiedApk -Force
            
            # Master APK directory for all agents and devices
            $workspaceRoot = (Split-Path -Parent $projectRoot)
            $apkMasterDir = Join-Path $workspaceRoot 'APK'
            New-Item -ItemType Directory -Force -Path $apkMasterDir | Out-Null
            Copy-Item -LiteralPath $apk -Destination (Join-Path $apkMasterDir 'RideLink-Thai-latest.apk') -Force
            Copy-Item -LiteralPath $apk -Destination (Join-Path $apkMasterDir 'RideLink-Thai-v0.2.1-debug.apk') -Force

            Write-Output "APK: $copiedApk"
            Write-Output "Master APK: $(Join-Path $apkMasterDir 'RideLink-Thai-latest.apk')"
            Get-FileHash -LiteralPath $copiedApk -Algorithm SHA256 | Select-Object Hash,Path
        }
    }
} finally {
    Pop-Location
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
