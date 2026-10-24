param([string]$WorkRoot = '')
$ErrorActionPreference = 'Stop'
$desktopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $WorkRoot) { $WorkRoot = Join-Path $desktopRoot '.toolchain\release' }
$WorkRoot = [IO.Path]::GetFullPath($WorkRoot)
New-Item -ItemType Directory -Force -Path $WorkRoot | Out-Null
$package = Join-Path $WorkRoot 'python.3.13.9.nupkg'
$url = 'https://api.nuget.org/v3-flatcontainer/python/3.13.9/python.3.13.9.nupkg'
$expected = 'ea7541c98f77b6f344460b88db6e677573069979bcbf0ba18747cc2c125aba25'
if (-not (Test-Path -LiteralPath $package)) { Invoke-WebRequest $url -OutFile $package }
if ((Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'Official Python package digest mismatch.'
}
$pythonRoot = Join-Path $WorkRoot 'python'
$basePython = Join-Path $pythonRoot 'tools\python.exe'
if (Test-Path -LiteralPath $pythonRoot) { throw 'Choose a fresh WorkRoot; existing release runtimes are not reused.' }
if (-not (Test-Path -LiteralPath $basePython)) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($package, $pythonRoot)
}
$signature = Get-AuthenticodeSignature -LiteralPath $basePython
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Python Software Foundation*') {
    throw 'Official Python Authenticode signature validation failed.'
}
$venv = Join-Path $WorkRoot 'venv'
& $basePython -I -m venv $venv
if ($LASTEXITCODE -ne 0) { throw 'Cannot create the isolated release environment.' }
$python = Join-Path $venv 'Scripts\python.exe'
& $python -I -m pip --isolated install --no-cache-dir --require-hashes --only-binary=:all: --index-url https://pypi.org/simple -r (Join-Path $desktopRoot 'requirements-release.lock.txt')
if ($LASTEXITCODE -ne 0) { throw 'Verified release dependencies could not be installed.' }
& $python -I -m pip check
if ($LASTEXITCODE -ne 0) { throw 'Release dependency consistency check failed.' }
Write-Output "Release Python: $python"
