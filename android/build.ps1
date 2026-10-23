param([switch]$DebugBuild, [switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Prefer a configured JDK; this workstation can reuse PyCharm's bundled JBR.
if (-not $env:JAVA_HOME) {
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java.Source) }
    else {
        $jbr = Get-ChildItem -Path "$env:ProgramFiles\JetBrains\*\jbr" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($jbr) { $env:JAVA_HOME = $jbr.FullName }
    }
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    throw 'Install JDK 17 or 21 and set JAVA_HOME, or use the GitHub Actions build.'
}
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk -and (Test-Path -LiteralPath '.toolchain\sdk\platforms\android-35\android.jar')) { $sdk = Join-Path $PSScriptRoot '.toolchain\sdk' }
if (-not $sdk) { throw 'Set ANDROID_HOME to an SDK with Android 35 and build-tools 35.0.0, or use GitHub Actions.' }
$env:ANDROID_HOME = $sdk
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.toolchain\gradle-home'
$sdkProperty = $sdk.Replace('\', '/')
Set-Content -LiteralPath 'local.properties' -Encoding ASCII -Value "sdk.dir=$sdkProperty"

if (-not $DebugBuild -and -not (Test-Path -LiteralPath 'keystore.properties')) {
    New-Item -ItemType Directory -Force -Path '.signing' | Out-Null
    if (Test-Path -LiteralPath '.signing\release.jks') { throw 'Signing key already exists. Restore keystore.properties; do not replace the key.' }
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32
    $rng.GetBytes($bytes)
    $env:OUC_SIGNING_PASSWORD = [Convert]::ToBase64String($bytes)
    try {
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore '.signing\release.jks' -storetype PKCS12 -alias ouc-release -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=OUC Network Helper,OU=Personal Tools,O=Personal,C=CN' -storepass:env OUC_SIGNING_PASSWORD -keypass:env OUC_SIGNING_PASSWORD -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
        # Properties escaping preserves generated passwords exactly.
        $escaped = $env:OUC_SIGNING_PASSWORD.Replace('\','\\').Replace('=','\=').Replace(':','\:')
        Set-Content -LiteralPath 'keystore.properties' -Encoding ASCII -Value @(
            'storeFile=.signing/release.jks', "storePassword=$escaped", 'keyAlias=ouc-release', "keyPassword=$escaped"
        )
    } finally { Remove-Item Env:OUC_SIGNING_PASSWORD -ErrorAction SilentlyContinue; $rng.Dispose() }
    Write-Host 'Generated a local release key. Back up .signing and keystore.properties privately before future updates.'
}

$tasks = @()
if (-not $SkipChecks) { $tasks += @(':app:testDebugUnitTest', ':app:lintDebug') }
$tasks += $(if ($DebugBuild) { ':app:assembleDebug' } else { ':app:assembleRelease' })
$localGradle = Join-Path $PSScriptRoot '.toolchain\gradle-8.9\bin\gradle.bat'
if (Test-Path -LiteralPath $localGradle) { & $localGradle @tasks --no-daemon }
else { & '.\gradlew.bat' @tasks --no-daemon }
if ($LASTEXITCODE -ne 0) { throw 'Build failed. See Gradle output above.' }
$variant = if ($DebugBuild) { 'debug' } else { 'release' }
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\$variant\app-$variant.apk"
New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
$metadata = Get-Content -LiteralPath (Join-Path $PSScriptRoot "app\build\outputs\apk\$variant\output-metadata.json") -Raw | ConvertFrom-Json
$versionName = $metadata.elements[0].versionName
if ($versionName -notmatch '^[0-9A-Za-z][0-9A-Za-z.+-]*$') { throw 'Invalid APK version in output metadata.' }
$artifactVersion = if ($variant -eq 'debug' -and $versionName.EndsWith('-debug')) { $versionName.Substring(0, $versionName.Length - 6) } else { $versionName }
$destination = Join-Path $PSScriptRoot "dist\OUNet-$artifactVersion-$variant.apk"
Copy-Item -LiteralPath $apk -Destination $destination -Force
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$destination.sha256" -Encoding ASCII -Value "$hash  $([System.IO.Path]::GetFileName($destination))"
if ($variant -eq 'release') {
    & python (Join-Path $PSScriptRoot 'release_metadata.py') --apk $destination --apk-info (Join-Path $PSScriptRoot 'app\build\outputs\apk\release\output-metadata.json') --apksigner (Join-Path $sdk 'build-tools\35.0.0\apksigner.bat') --notes (Join-Path $PSScriptRoot '..\docs\ANDROID_V1_3_4_RELEASE_NOTES.md')
    if ($LASTEXITCODE -ne 0) { throw 'Public Android release metadata verification failed.' }
}
Write-Host "APK ready: $destination"
