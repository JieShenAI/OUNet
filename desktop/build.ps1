param([string]$IsccPath = "", [string]$PythonPath = "", [switch]$SkipTests, [switch]$SkipInstaller)
$ErrorActionPreference = 'Stop'
$desktopRoot = $PSScriptRoot
$desktopPython = if ($PythonPath) { [IO.Path]::GetFullPath($PythonPath) } else { Join-Path $desktopRoot '.venv\Scripts\python.exe' }
if (-not (Test-Path -LiteralPath $desktopPython)) { throw '请先按 README 创建独立环境并安装 requirements-build.txt。' }
$savedPythonHome = $env:PYTHONHOME
$savedPythonPath = $env:PYTHONPATH
$savedNoUserSite = $env:PYTHONNOUSERSITE
Remove-Item Env:PYTHONHOME,Env:PYTHONPATH -ErrorAction SilentlyContinue
$env:PYTHONNOUSERSITE = '1'
function Invoke-Checked {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$StepArguments)
    & $desktopPython @StepArguments
    if ($LASTEXITCODE -ne 0) { throw "构建步骤失败，退出码 $LASTEXITCODE" }
}
Push-Location $desktopRoot
try {
    $releaseVersion = & $desktopPython 'tools\check_versions.py'
    if ($LASTEXITCODE -ne 0) { throw 'Version validation failed.' }
    $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not $SkipInstaller) {
        if (-not $IsccPath) {
            $IsccPath = Join-Path $desktopRoot '.toolchain\inno\ISCC.exe'
            if (-not (Test-Path -LiteralPath $IsccPath)) { $IsccPath = Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe' }
        }
        Invoke-Checked @('tools\check_build_environment.py','--output','.cache\build-provenance.json')
        $basePython = & $desktopPython -c 'import sys; print(sys._base_executable)'
        $signature = Get-AuthenticodeSignature -LiteralPath $basePython
        if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Python Software Foundation*') { throw 'Official Python signature validation failed.' }
        $compilerSignature = Get-AuthenticodeSignature -LiteralPath $IsccPath
        if ($compilerSignature.Status -ne 'Valid') { throw 'Installer compiler signature validation failed.' }
    }
    Invoke-Checked @('tools\check_versions.py')
    Invoke-Checked @('tools\prepare_assets.py')
    Invoke-Checked @('tools\collect_licenses.py')
    if (-not $SkipTests) {
        Invoke-Checked @('-m','unittest','discover','-s','tests','-q')
        Invoke-Checked @('-m','unittest','discover','-s','..\tests','-q')
        Invoke-Checked @('run.py','--smoke-test','--smoke-output','screenshots')
        Invoke-Checked @('tools\test_ui.py')
    }
    Invoke-Checked @('-m','PyInstaller','--clean','--noconfirm','--distpath','dist','--workpath','build','OUCNetworkAssistant.spec')
    if (-not $SkipInstaller) {
        & $windowsPowerShell -NoProfile -File 'tools\scan_release.ps1' -TargetPaths 'dist\OUNet' -ReportPath '.cache\payload-security-scan.json' -Version $releaseVersion -UpdateSignatures
        if ($LASTEXITCODE -ne 0) { throw 'Frozen payload security scan failed.' }
    }
    if (-not $SkipTests) {
        & (Join-Path $desktopRoot 'tools\test_runtime.ps1')
    }
    if (-not $SkipInstaller) {
        if (-not $IsccPath) {
            $IsccPath = Join-Path $desktopRoot '.toolchain\inno\ISCC.exe'
            if (-not (Test-Path -LiteralPath $IsccPath)) {
                $IsccPath = Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'
            }
        }
        if (-not (Test-Path -LiteralPath $IsccPath)) { throw '找不到 Inno Setup 编译器，请通过 -IsccPath 指定 ISCC.exe。' }
        & $IsccPath '/Qp' 'installer\OUCNetworkAssistant.iss'
        if ($LASTEXITCODE -ne 0) { throw '安装器编译失败。' }
    }
    if (-not $SkipInstaller) {
        Invoke-Checked @('tools\prepare_release.py')
        # Run in Windows PowerShell so Defender's module does not depend on
        # PowerShell 7 remoting. The comma-separated array is passed in-process.
        $scanScript = Join-Path $desktopRoot 'tools\scan_release.ps1'
        $scanTargets = @((Join-Path $desktopRoot 'dist\OUNet'),(Join-Path $desktopRoot "dist\OUNet-$releaseVersion-Setup-x64.exe"),(Join-Path $desktopRoot "dist\OUNet-$releaseVersion-Windows-x64-portable.zip"))
        $scanCommand = "& '" + $scanScript.Replace("'","''") + "' -TargetPaths @(" + (($scanTargets | ForEach-Object { "'" + $_.Replace("'","''") + "'" }) -join ',') + ") -ReportPath 'dist\windows-security-scan.json' -Version '" + $releaseVersion + "' -ProvenancePath '.cache\build-provenance.json'"
        & $windowsPowerShell -NoProfile -Command $scanCommand
        if ($LASTEXITCODE -ne 0) { throw 'Release artifacts security scan failed.' }
        $releaseVersion = & $desktopPython 'tools\check_versions.py'
        if ($LASTEXITCODE -ne 0) { throw 'Version validation failed.' }
        $bundleArguments = @('..\tools\release_bundle.py','--platform','windows','--version',$releaseVersion)
        if (-not $SkipTests) { $bundleArguments += '--checks-passed' }
        Invoke-Checked @bundleArguments
    }
} finally {
    Pop-Location
    $env:PYTHONHOME = $savedPythonHome
    $env:PYTHONPATH = $savedPythonPath
    $env:PYTHONNOUSERSITE = $savedNoUserSite
}
