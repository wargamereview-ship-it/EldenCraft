# Private runtime only; does not change the system Python or execution policy.
$ErrorActionPreference = 'Stop'
try {
    $package = $PSScriptRoot
    $spec = (Get-Content -Raw -LiteralPath (Join-Path $package 'dependencies.json') | ConvertFrom-Json).python_windows
    $runtime = Join-Path $package '.python'
    $python = Join-Path $runtime 'python\python.exe'
    if (-not (Test-Path -LiteralPath $python)) {
        $downloads = Join-Path $package 'downloads'
        New-Item -ItemType Directory -Force -Path $downloads | Out-Null
        $name = [Uri]::UnescapeDataString(([Uri]$spec.url).AbsolutePath.Split('/')[-1])
        $archive = Join-Path $downloads ($spec.sha256.Substring(0,16) + '-' + $name)
        if (-not (Test-Path -LiteralPath $archive)) {
            if ($args -contains '--offline') { throw 'The private Python runtime is not cached. Start setup online once before using --offline.' }
            Write-Host 'Downloading the private installer runtime...'
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -UseBasicParsing -Uri $spec.url -OutFile ($archive + '.part')
            Move-Item -Force -LiteralPath ($archive + '.part') -Destination $archive
        }
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne $spec.sha256) {
            Remove-Item -LiteralPath $archive
            throw 'Installer runtime checksum mismatch. Run setup again to download a clean copy.'
        }
        $staging = $runtime + '-new'
        if (Test-Path -LiteralPath $staging) { Remove-Item -Recurse -Force -LiteralPath $staging }
        New-Item -ItemType Directory -Force -Path $staging | Out-Null
        & tar.exe -xzf $archive -C $staging
        if ($LASTEXITCODE -ne 0) { throw 'Could not extract the installer runtime. Windows 10/11 tar.exe is required.' }
        if (Test-Path -LiteralPath $runtime) { Remove-Item -Recurse -Force -LiteralPath $runtime }
        Move-Item -LiteralPath $staging -Destination $runtime
    }
    Set-Content -LiteralPath (Join-Path $runtime 'python\INSTALLER_RUNTIME') -Value 'EldenCraft private installer runtime'
    $env:ELDENCRAFT_SETUP_RUNTIME = Join-Path $runtime 'python'
    $env:PYTHONUTF8 = '1'
    & $python (Join-Path $package 'setup.py') @args
    exit $LASTEXITCODE
} catch {
    Write-Host ("Setup needs attention: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
