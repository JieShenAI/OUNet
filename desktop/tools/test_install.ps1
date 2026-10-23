# Isolated integration check. Refuses to touch an existing real installation.
$ErrorActionPreference = 'Stop'
$desktopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cacheRoot = [IO.Path]::GetFullPath((Join-Path $desktopRoot '.cache'))
$installRoot = [IO.Path]::GetFullPath((Join-Path $cacheRoot 'install-test'))
if (-not $installRoot.StartsWith($cacheRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test directory.' }
if (Test-Path -LiteralPath $installRoot) { throw 'Test directory already exists; preserve it for inspection.' }
$uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{A6ED0C7B-B925-4FB1-84B1-F90D81234E73}_is1'
if (Test-Path -LiteralPath $uninstallKey) { throw 'A registered installation exists. Integration test aborted.' }
$startupProperties = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
if ($startupProperties.PSObject.Properties.Name -contains 'OUCNetworkAssistant') { throw 'An existing startup item exists. Integration test aborted.' }
$installer = Join-Path $desktopRoot 'dist\OUNet-0.3.0-Setup-x64.exe'
if (-not (Test-Path -LiteralPath $installer)) { throw 'Build the installer first.' }
$installArguments = @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/NOICONS','/TASKS=""',('/DIR="'+$installRoot+'"'),('/LOG="'+(Join-Path $cacheRoot 'install-test.log')+'"'))
function Invoke-TestProcess {
    param([string]$File,[string[]]$Options)
    $process = Start-Process -FilePath $File -ArgumentList $Options -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Test process failed: $($process.ExitCode). See .cache logs." }
}
Invoke-TestProcess -File $installer -Options $installArguments
$exe = Join-Path $installRoot 'OUNet.exe'
$smokeOutput = Join-Path $cacheRoot 'installed-smoke'
Invoke-TestProcess -File $exe -Options @('--smoke-test','--smoke-output',('"'+$smokeOutput+'"'))
$sentinel = Join-Path $installRoot 'test-preserved.txt'
[IO.File]::WriteAllText($sentinel,'synthetic upgrade preservation test')
Invoke-TestProcess -File $installer -Options $installArguments
if (-not (Test-Path -LiteralPath $sentinel)) { throw 'Upgrade removed the synthetic preserved file.' }
$uninstaller = [IO.Path]::GetFullPath((Join-Path $installRoot 'unins000.exe'))
if (-not $uninstaller.StartsWith($installRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe uninstaller path.' }
Invoke-TestProcess -File $uninstaller -Options @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/LOG="'+(Join-Path $cacheRoot 'uninstall-test.log')+'"'))
if ((Test-Path -LiteralPath $uninstallKey) -or (Test-Path -LiteralPath $exe)) { throw 'Uninstall did not remove its registration or executable.' }
# Remove only our known sentinel and an empty test folder; never recursively delete.
Remove-Item -LiteralPath $sentinel
if ((Get-ChildItem -LiteralPath $installRoot -Force | Measure-Object).Count -eq 0) { Remove-Item -LiteralPath $installRoot }
[pscustomobject]@{install=$true;installedApp=$true;upgrade=$true;uninstall=$true;realCredentialsUsed=$false;realNetworkLoginAttempted=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $cacheRoot 'integration-report.json') -Encoding utf8
Write-Output 'Isolated installation, launch, upgrade and uninstall checks passed.'
