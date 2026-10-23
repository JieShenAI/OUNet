param([string]$IsccPath = "", [switch]$SkipTests, [switch]$SkipInstaller)
$ErrorActionPreference = 'Stop'
$desktopRoot = $PSScriptRoot
$desktopPython = Join-Path $desktopRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $desktopPython)) { throw '请先按 README 创建独立环境并安装 requirements-build.txt。' }
function Invoke-Checked {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$StepArguments)
    & $desktopPython @StepArguments
    if ($LASTEXITCODE -ne 0) { throw "构建步骤失败，退出码 $LASTEXITCODE" }
}
Push-Location $desktopRoot
try {
    Invoke-Checked @('tools\check_versions.py')
    Invoke-Checked @('tools\prepare_assets.py')
    Invoke-Checked @('tools\collect_licenses.py')
    if (-not $SkipTests) {
        Invoke-Checked @('-m','unittest','discover','-s','tests','-q')
        Invoke-Checked @('-m','unittest','discover','-s','..\tests','-q')
        Invoke-Checked @('run.py','--smoke-test','--smoke-output','screenshots')
        Invoke-Checked @('tools\test_ui.py')
    }
    Invoke-Checked @('-m','PyInstaller','--noconfirm','--distpath','dist','--workpath','build','OUCNetworkAssistant.spec')
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
        $releaseVersion = & $desktopPython 'tools\check_versions.py'
        if ($LASTEXITCODE -ne 0) { throw 'Version validation failed.' }
        $bundleArguments = @('..\tools\release_bundle.py','--platform','windows','--version',$releaseVersion)
        if (-not $SkipTests) { $bundleArguments += '--checks-passed' }
        Invoke-Checked @bundleArguments
    }
} finally { Pop-Location }
