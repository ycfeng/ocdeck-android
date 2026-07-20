$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion -lt [Version]'7.3') {
    throw 'verify-bridge-reproducibility.ps1 requires PowerShell 7.3 or newer'
}
$PSNativeCommandUseErrorActionPreference = $true

function Assert-NativeSuccess([string]$Description) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = (& git -C (Join-Path $ScriptDir '..\..') rev-parse --show-toplevel | Out-String).Trim()
Assert-NativeSuccess 'resolve repository root'
$Commit = (& git -C $RootDir rev-parse HEAD | Out-String).Trim()
Assert-NativeSuccess 'resolve repository commit'
$Status = (& git -C $RootDir status --porcelain --untracked-files=all | Out-String).Trim()
Assert-NativeSuccess 'inspect repository status'
if ($Status) {
    throw 'Bridge reproducibility verification requires a clean checkout'
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $LocalProperties = Join-Path $RootDir 'local.properties'
    if (Test-Path -LiteralPath $LocalProperties) {
        $SdkDirLine = Get-Content -LiteralPath $LocalProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($SdkDirLine) {
            $SdkDir = ($SdkDirLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
            $env:ANDROID_HOME = $SdkDir
            $env:ANDROID_SDK_ROOT = $SdkDir
        }
    }
}

$TempParent = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$TempRoot = Join-Path $TempParent "ocdeck-bridge-repro-$([Guid]::NewGuid().ToString('N'))"
$SecondaryRoot = Join-Path $TempRoot 'secondary-checkout-with-a-distinct-absolute-path'
$WorktreeAdded = $false

function Invoke-BridgeBuild([string]$Checkout, [string]$CacheRoot) {
    $EnvironmentKeys = @('GOCACHE', 'GOMODCACHE', 'GOPATH', 'GOENV', 'GOTOOLCHAIN')
    $Previous = @{}
    foreach ($Key in $EnvironmentKeys) {
        $Previous[$Key] = [Environment]::GetEnvironmentVariable($Key, 'Process')
    }
    try {
        New-Item -ItemType Directory -Force -Path `
            (Join-Path $CacheRoot 'go-build'), `
            (Join-Path $CacheRoot 'go-mod'), `
            (Join-Path $CacheRoot 'gopath') | Out-Null
        $env:GOCACHE = Join-Path $CacheRoot 'go-build'
        $env:GOMODCACHE = Join-Path $CacheRoot 'go-mod'
        $env:GOPATH = Join-Path $CacheRoot 'gopath'
        $env:GOENV = 'off'
        $env:GOTOOLCHAIN = 'local'
        & (Join-Path $Checkout 'frpc-stcp-visitor-go\build-aar.ps1')
        Assert-NativeSuccess 'build GoMobile bridge'
    }
    finally {
        foreach ($Key in $EnvironmentKeys) {
            [Environment]::SetEnvironmentVariable($Key, $Previous[$Key], 'Process')
        }
    }
}

try {
    New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
    & git -C $RootDir -c core.autocrlf=false -c core.eol=lf worktree add --detach $SecondaryRoot $Commit
    Assert-NativeSuccess 'create secondary checkout'
    $WorktreeAdded = $true

    Invoke-BridgeBuild $RootDir (Join-Path $TempRoot 'cache-primary')
    Invoke-BridgeBuild $SecondaryRoot (Join-Path $TempRoot 'cache-secondary')

    $Versions = @{}
    Get-Content -LiteralPath (Join-Path $RootDir 'frpc-stcp-visitor-go\bridge-versions.properties') |
        ForEach-Object {
            if ($_ -and -not $_.StartsWith('#')) {
                $Parts = $_.Split('=', 2)
                $Versions[$Parts[0]] = $Parts[1]
            }
        }
    $BridgeVersion = $Versions['BRIDGE_VERSION']
    if (-not $BridgeVersion) {
        throw 'BRIDGE_VERSION is missing'
    }
    $ArtifactName = "frpc-stcp-visitor-gobridge-$BridgeVersion"
    $RepositoryRelative = "frpc-stcp-visitor-go\build\repo\io\github\ycfeng\ocdeck\frpc-stcp-visitor-gobridge\$BridgeVersion"
    $PrimaryRepository = Join-Path $RootDir $RepositoryRelative
    $SecondaryRepository = Join-Path $SecondaryRoot $RepositoryRelative
    $ExpectedFiles = @(
        "$ArtifactName-sources.jar",
        "$ArtifactName.aar",
        "$ArtifactName.aar.api.txt",
        "$ArtifactName.aar.frp-provenance.json",
        "$ArtifactName.aar.native.json",
        "$ArtifactName.aar.provenance.json",
        "$ArtifactName.aar.sha256",
        "$ArtifactName.pom"
    ) | Sort-Object

    foreach ($Repository in @($PrimaryRepository, $SecondaryRepository)) {
        $ActualFiles = @(Get-ChildItem -LiteralPath $Repository -File | Sort-Object Name | ForEach-Object Name)
        if ($ActualFiles.Count -ne $ExpectedFiles.Count -or
            (Compare-Object -ReferenceObject $ExpectedFiles -DifferenceObject $ActualFiles)) {
            throw 'Bridge repository contains an unexpected artifact set'
        }
        foreach ($Artifact in $ExpectedFiles) {
            $ArtifactPath = Join-Path $Repository $Artifact
            if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf) -or
                (Get-Item -LiteralPath $ArtifactPath).Length -eq 0) {
                throw "Missing reproducibility artifact: $Artifact"
            }
        }
    }

    foreach ($Artifact in $ExpectedFiles) {
        $PrimaryPath = Join-Path $PrimaryRepository $Artifact
        $SecondaryPath = Join-Path $SecondaryRepository $Artifact
        $PrimaryFile = Get-Item -LiteralPath $PrimaryPath
        $SecondaryFile = Get-Item -LiteralPath $SecondaryPath
        $PrimaryHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $PrimaryPath).Hash
        $SecondaryHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $SecondaryPath).Hash
        if ($PrimaryFile.Length -ne $SecondaryFile.Length -or $PrimaryHash -ne $SecondaryHash) {
            throw "Bridge artifact is not reproducible across checkout paths: $Artifact"
        }
    }

    'Bridge artifacts are byte-for-byte reproducible across distinct checkout paths.'
}
finally {
    if ($WorktreeAdded) {
        try {
            & git -C $RootDir worktree remove --force $SecondaryRoot 2>$null
        }
        catch {
            # Cleanup must not replace the build or comparison failure.
        }
    }
    if (Test-Path -LiteralPath $TempRoot) {
        try {
            Remove-Item -LiteralPath $TempRoot -Recurse -Force
        }
        catch {
            # The runner will clean its temporary directory after the job.
        }
    }
}
