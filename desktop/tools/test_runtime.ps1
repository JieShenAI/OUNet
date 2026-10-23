# Validate the frozen app with developer Python/Qt paths removed from the child.
$ErrorActionPreference = 'Stop'
$desktopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$exe = Join-Path $desktopRoot 'dist\OUNet\OUNet.exe'
$output = Join-Path $desktopRoot 'screenshots\frozen'
$cache = Join-Path $desktopRoot '.cache'
New-Item -ItemType Directory -Force -Path $output,$cache | Out-Null
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = $exe
$start.Arguments = '--smoke-test --smoke-output "'+$output+'"'
$start.WorkingDirectory = $cache
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
$start.EnvironmentVariables['PATH'] = $env:SystemRoot+'\System32;'+$env:SystemRoot
foreach ($name in @('PYTHONHOME','PYTHONPATH','QT_PLUGIN_PATH','QML2_IMPORT_PATH','QML_IMPORT_PATH','CONDA_PREFIX')) {
    $start.EnvironmentVariables.Remove($name)
}
$process = [Diagnostics.Process]::Start($start)
if (-not $process.WaitForExit(60000)) { $process.Kill(); throw 'Frozen render test timed out.' }
if ($process.ExitCode -ne 0) { throw "Frozen application failed: $($process.ExitCode). See screenshots/frozen/crash.txt." }
$report = Get-Content -LiteralPath (Join-Path $output 'smoke-report.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $report.qmlLoaded -or $report.screenshots.Count -ne 4 -or $report.qmlWarnings.Count -ne 0 -or -not $report.dpapiRoundTrip -or -not $report.tlsVerificationEnabled) { throw 'Frozen report did not pass all checks.' }
[pscustomobject]@{cleanDeveloperPath=$true;exitCode=$process.ExitCode;qmlLoaded=$report.qmlLoaded;dpapi=$report.dpapiRoundTrip;tlsVerification=$report.tlsVerificationEnabled;screenshots=$report.screenshots.Count} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $cache 'clean-runtime-report.json') -Encoding UTF8
Write-Output 'Frozen app passed with developer Python and Qt paths removed.'
