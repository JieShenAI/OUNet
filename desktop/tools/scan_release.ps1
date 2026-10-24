param(
    [Parameter(Mandatory=$true)][string[]]$TargetPaths,
    [Parameter(Mandatory=$true)][string]$ReportPath,
    [Parameter(Mandatory=$true)][string]$Version,
    [string]$ProvenancePath = '',
    [switch]$UpdateSignatures
)
$ErrorActionPreference = 'Stop'
function Get-Identity([string]$Path) {
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if (-not $item.PSIsContainer) {
        return [ordered]@{kind='file';name=$item.Name;size=$item.Length;sha256=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $rows = @(Get-ChildItem -LiteralPath $item.FullName -File -Recurse | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($item.FullName.Length + 1).Replace('\','/') + ' ' + $_.Length + ' ' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    })
    if ($rows.Count -eq 0) { throw 'Cannot scan an empty payload directory.' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = -join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($rows -join "`n"))) | ForEach-Object {$_.ToString('x2')}) }
    finally { $sha.Dispose() }
    return [ordered]@{kind='directory';name=$item.Name;fileCount=$rows.Count;treeSha256=$digest}
}
$platform = Get-ChildItem -LiteralPath "$env:ProgramData\Microsoft\Windows Defender\Platform" -Directory | Where-Object {$_.Name -match '^\d+\.'} | Sort-Object Name -Descending | Select-Object -First 1
if (-not $platform) { throw 'Microsoft Defender scanner is unavailable.' }
$scanner = Join-Path $platform.FullName 'MpCmdRun.exe'
if ($UpdateSignatures) {
    & $scanner -SignatureUpdate
    if ($LASTEXITCODE -ne 0) { throw 'Defender security intelligence update failed.' }
}
$state = Get-MpComputerStatus
if (-not $state.AntivirusEnabled -or -not $state.RealTimeProtectionEnabled) { throw 'Release scanning requires active Defender and real-time protection.' }
if ((Get-Date).ToUniversalTime() - $state.AntivirusSignatureLastUpdated.ToUniversalTime() -gt [TimeSpan]::FromHours(24)) { throw 'Defender security intelligence is older than 24 hours.' }
$results = @()
$passed = $true
foreach ($target in $TargetPaths) {
    $before = Get-Identity $target
    # This mode scans archives and ignores path exclusions. It changes no
    # preferences and leaves all real-time protection enabled.
    $output = @(& $scanner -Scan -ScanType 3 -File ([IO.Path]::GetFullPath($target)) -DisableRemediation 2>&1)
    $code = $LASTEXITCODE
    $after = Get-Identity $target
    $same = ($before | ConvertTo-Json -Compress) -ceq ($after | ConvertTo-Json -Compress)
    $clean = $code -eq 0 -and $same
    $passed = $passed -and $clean
    $result = [ordered]@{target=$before;exitCode=$code;unchangedDuringScan=$same;noThreatsDetected=$clean}
    $results += $result
    Write-Output ($before.name + ': scanner exit ' + $code + ', unchanged=' + $same)
    $output | ForEach-Object { Write-Output $_ }
}
$report = [ordered]@{schemaVersion=1;version=$Version;scannedAtUtc=[DateTime]::UtcNow.ToString('o');engineVersion=$state.AMEngineVersion;signatureVersion=$state.AntivirusSignatureVersion;signatureUpdatedUtc=$state.AntivirusSignatureLastUpdated.ToUniversalTime().ToString('o');realTimeProtectionEnabled=[bool]$state.RealTimeProtectionEnabled;archivesScanned=$true;exclusionsIgnored=$true;allTargetsClean=$passed;results=$results}
if ($ProvenancePath) { $report.build = Get-Content -LiteralPath $ProvenancePath -Raw -Encoding UTF8 | ConvertFrom-Json }
$fullReport = [IO.Path]::GetFullPath($ReportPath)
New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($fullReport)) | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullReport -Encoding UTF8
if (-not $passed) { throw 'Release blocked: scan detected a threat, failed, or the scanned files changed.' }
