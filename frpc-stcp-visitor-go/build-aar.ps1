$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion -lt [Version]'7.3') {
    throw 'build-aar.ps1 requires PowerShell 7.3 or newer'
}
$PSNativeCommandUseErrorActionPreference = $true
$env:GOWORK = 'off'
$env:GOFLAGS = ''

function Assert-NativeSuccess([string]$Description) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Resolve-Path (Join-Path $ScriptDir '..')
$VersionsFile = Join-Path $ScriptDir 'bridge-versions.properties'
$Versions = @{}
Get-Content -LiteralPath $VersionsFile | ForEach-Object {
    if ($_ -and -not $_.StartsWith('#')) {
        $Parts = $_.Split('=', 2)
        $Versions[$Parts[0]] = $Parts[1]
    }
}

$BridgeVersion = $Versions['BRIDGE_VERSION']
$RequiredGoVersion = $Versions['GO_VERSION']
$XMobileVersion = $Versions['XMOBILE_VERSION']
$AndroidApi = $Versions['ANDROID_API']
$NdkVersion = $Versions['NDK_VERSION']
$ArtifactName = "frpc-stcp-visitor-gobridge-$BridgeVersion"
$AarOutput = Join-Path $ScriptDir '..\frpc-stcp-visitor\libs\frpc-stcp-visitor.aar'
$SourcesOutput = $AarOutput -replace '\.aar$', '-sources.jar'
$RepoDir = Join-Path $ScriptDir "build\repo\io\github\ycfeng\ocdeck\frpc-stcp-visitor-gobridge\$BridgeVersion"
$RepoAar = Join-Path $RepoDir "$ArtifactName.aar"
$RepoSources = Join-Path $RepoDir "$ArtifactName-sources.jar"
$RepoPom = Join-Path $RepoDir "$ArtifactName.pom"
$RepoSha = "$RepoAar.sha256"
$RepoApi = "$RepoAar.api.txt"
$RepoProvenance = "$RepoAar.provenance.json"
$RepoFrpProvenance = "$RepoAar.frp-provenance.json"
$RepoNative = "$RepoAar.native.json"
$PatchProvenance = Join-Path $ScriptDir 'build\frp-patch-provenance.json'
$ModuleProxyConfig = Join-Path $ScriptDir 'build\module-proxy.properties'
$StagingDir = Join-Path $ScriptDir 'build\aar-staging'
$StagedAar = Join-Path $StagingDir 'frpc-stcp-visitor.aar'
$StagedSources = Join-Path $StagingDir 'frpc-stcp-visitor-sources.jar'
$StagedApi = Join-Path $StagingDir 'bridge-api.txt'
$PreNativeReport = Join-Path $StagingDir 'native-preflight.json'
$ValidatedNativeReport = Join-Path $StagingDir 'native-validated.json'
$EmbeddedProvenance = Join-Path $StagingDir 'bridge-provenance.json'
$ExternalProvenance = Join-Path $StagingDir 'bridge-provenance-with-hash.json'
$ApiCheckDir = Join-Path $StagingDir 'api-check'
$ExpectedApi = Join-Path $ScriptDir 'api\frpcstcpvisitor.txt'
$BridgeClass = 'io.github.ycfeng.ocdeck.frpcstcpvisitor.gobridge.frpcstcpvisitor.Frpcstcpvisitor'

if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
        [Environment]::GetEnvironmentVariable('Path', 'User')
}
if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw 'go was not found on PATH'
}

$ActualGoVersion = (& go env GOVERSION | Out-String).Trim()
Assert-NativeSuccess 'go env GOVERSION'
if ($ActualGoVersion -ne $RequiredGoVersion) {
    throw "Go version mismatch: got $ActualGoVersion, require $RequiredGoVersion"
}

$GoPath = (& go env GOPATH | Out-String).Trim()
Assert-NativeSuccess 'go env GOPATH'
$GoPathEntries = $GoPath.Split([IO.Path]::PathSeparator, [StringSplitOptions]::RemoveEmptyEntries)
if ($GoPathEntries.Count -eq 0) {
    throw 'go env GOPATH returned no usable entries'
}
$PrimaryGoPath = $GoPathEntries[0]
$GoBin = Join-Path $PrimaryGoPath 'bin'
if ($env:Path -notlike "*$GoBin*") {
    $env:Path = "$GoBin;$env:Path"
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
$SdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if (-not $SdkRoot) {
    throw 'Android SDK path is not configured'
}
$NdkDir = Join-Path $SdkRoot "ndk\$NdkVersion"
if (-not (Test-Path -LiteralPath $NdkDir)) {
    throw "Android NDK $NdkVersion is missing"
}
$env:ANDROID_NDK_HOME = $NdkDir

Push-Location $ScriptDir
try {
    & go run ./cmd/preparefrp
    Assert-NativeSuccess 'preparefrp'
    & go run ./cmd/preparemoduleproxy
    Assert-NativeSuccess 'preparemoduleproxy'

    $BuildConfig = @{}
    Get-Content -LiteralPath $ModuleProxyConfig | ForEach-Object {
        if ($_ -and -not $_.StartsWith('#')) {
            $Parts = $_.Split('=', 2)
            $BuildConfig[$Parts[0]] = $Parts[1]
        }
    }
    foreach ($Key in @('PROXY_URL', 'BIND_PACKAGE', 'BIND_MODULE_DIR', 'GONOSUMDB')) {
        if (-not $BuildConfig[$Key]) {
            throw 'Generated module proxy configuration is incomplete'
        }
    }
    $BindModuleRelative = $BuildConfig['BIND_MODULE_DIR'].Replace('/', [IO.Path]::DirectorySeparatorChar)
    $BindModuleDir = Join-Path $ScriptDir $BindModuleRelative
    $env:GOPROXY = "$($BuildConfig['PROXY_URL']),https://proxy.golang.org,direct"
    $env:GONOSUMDB = $BuildConfig['GONOSUMDB']
    $env:GONOPROXY = 'none'
    $env:GOPRIVATE = ''

    & go install golang.org/x/mobile/cmd/gomobile
    Assert-NativeSuccess 'install gomobile'
    & go install golang.org/x/mobile/cmd/gobind
    Assert-NativeSuccess 'install gobind'
    New-Item -ItemType Directory -Force -Path (Join-Path $PrimaryGoPath 'pkg\gomobile') | Out-Null

    Remove-Item -LiteralPath $AarOutput, $SourcesOutput -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $StagingDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $StagingDir, (Split-Path -Parent $AarOutput) | Out-Null

    Push-Location $BindModuleDir
    try {
        & gomobile bind `
            -target android `
            -androidapi $AndroidApi `
            -javapkg io.github.ycfeng.ocdeck.frpcstcpvisitor.gobridge `
            -trimpath `
            -ldflags '-s -w -extldflags=-Wl,-z,max-page-size=16384' `
            -o $StagedAar `
            $BuildConfig['BIND_PACKAGE']
        Assert-NativeSuccess 'gomobile bind'
    }
    finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $StagedAar)) {
        throw 'gomobile did not create the AAR'
    }
    if (-not (Test-Path -LiteralPath $StagedSources)) {
        throw 'gomobile did not create the sources JAR'
    }

    & go run ./cmd/normalizezip $StagedAar
    Assert-NativeSuccess 'normalize AAR'
    & go run ./cmd/normalizezip $StagedSources
    Assert-NativeSuccess 'normalize sources JAR'
    New-Item -ItemType Directory -Force -Path $ApiCheckDir | Out-Null
    Push-Location $ApiCheckDir
    try {
        & jar xf $StagedAar classes.jar
        Assert-NativeSuccess 'extract classes.jar'
        $ApiText = (& javap -classpath (Join-Path $ApiCheckDir 'classes.jar') $BridgeClass | Out-String).Trim()
        Assert-NativeSuccess 'javap bridge API'
    }
    finally {
        Pop-Location
    }
    $ExpectedApiText = [IO.File]::ReadAllText($ExpectedApi).Trim()
    $NormalizedApiText = $ApiText.Replace("`r`n", "`n")
    if ($NormalizedApiText -ne $ExpectedApiText.Replace("`r`n", "`n")) {
        throw 'GoMobile bridge API does not match the committed signature'
    }
    [IO.File]::WriteAllText($StagedApi, "$NormalizedApiText`n")

    $PreNativeReportText = (& go run ./cmd/checkaar `
        -native-only `
        -root $RootDir `
        -versions $VersionsFile `
        $StagedAar | Out-String).Trim()
    Assert-NativeSuccess 'preflight native AAR graph'
    [IO.File]::WriteAllText($PreNativeReport, "$PreNativeReportText`n")

    & go run ./cmd/writebridgeprovenance `
        -versions $VersionsFile `
        -native-report $PreNativeReport `
        -output $EmbeddedProvenance
    Assert-NativeSuccess 'write embedded bridge provenance'
    $NormalizeArguments = @(
        'run', './cmd/normalizezip',
        '--add-text', "META-INF/OCDECK/LICENSE.txt=$(Join-Path $RootDir 'LICENSE')",
        '--add-text', "META-INF/OCDECK/NOTICE.txt=$(Join-Path $RootDir 'NOTICE')",
        '--add-text', "META-INF/OCDECK/THIRD_PARTY_NOTICES.txt=$(Join-Path $RootDir 'THIRD_PARTY_NOTICES.txt')",
        '--add-text', "META-INF/OCDECK/TRADEMARKS.md=$(Join-Path $RootDir 'TRADEMARKS.md')",
        '--add-text', "META-INF/OCDECK/bridge-api.txt=$ExpectedApi",
        '--add-text', "META-INF/OCDECK/bridge-provenance.json=$EmbeddedProvenance",
        '--add-text', "META-INF/OCDECK/frp-patch-provenance.json=$PatchProvenance"
    )
    $LicenseFiles = @(Get-ChildItem -LiteralPath (Join-Path $RootDir 'third_party\licenses') -File | Sort-Object Name)
    if ($LicenseFiles.Count -eq 0) {
        throw 'No third-party license files found'
    }
    foreach ($License in $LicenseFiles) {
        $NormalizeArguments += @('--add-text', "META-INF/OCDECK/licenses/$($License.Name)=$($License.FullName)")
    }
    $NormalizeArguments += $StagedAar
    & go @NormalizeArguments
    Assert-NativeSuccess 'inject and normalize AAR metadata'

    $NativeReport = (& go run ./cmd/checkaar `
        -versions $VersionsFile `
        -root $RootDir `
        -api $ExpectedApi `
        -bridge-provenance $EmbeddedProvenance `
        -frp-provenance $PatchProvenance `
        $StagedAar | Out-String).Trim()
    Assert-NativeSuccess 'check AAR'
    [IO.File]::WriteAllText($ValidatedNativeReport, "$NativeReport`n")
    $AarSha256 = (& go run ./cmd/filehash $StagedAar | Out-String).Trim()
    Assert-NativeSuccess 'hash AAR'

    New-Item -ItemType Directory -Force -Path $RepoDir | Out-Null
    if (Test-Path -LiteralPath $RepoAar) {
        $ExistingSha256 = (& go run ./cmd/filehash $RepoAar | Out-String).Trim()
        Assert-NativeSuccess 'hash existing immutable AAR'
        if ($ExistingSha256 -ne $AarSha256) {
            throw "Immutable bridge version $BridgeVersion already exists with different bytes"
        }
    }
    else {
        Copy-Item -LiteralPath $StagedAar -Destination $RepoAar
    }
    $TemporaryAarOutput = "$AarOutput.tmp"
    Copy-Item -LiteralPath $StagedAar -Destination $TemporaryAarOutput -Force
    Move-Item -LiteralPath $TemporaryAarOutput -Destination $AarOutput -Force
    Copy-Item -LiteralPath $StagedSources -Destination $RepoSources -Force
    $TemporarySourcesOutput = "$SourcesOutput.tmp"
    Copy-Item -LiteralPath $StagedSources -Destination $TemporarySourcesOutput -Force
    Move-Item -LiteralPath $TemporarySourcesOutput -Destination $SourcesOutput -Force

    $Pom = @"
<?xml version="1.0" encoding="UTF-8"?>
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.ycfeng.ocdeck</groupId>
  <artifactId>frpc-stcp-visitor-gobridge</artifactId>
  <version>$BridgeVersion</version>
  <packaging>aar</packaging>
  <name>OC Deck frpc STCP visitor GoMobile bridge</name>
  <description>Native frpc STCP visitor bridge used by the OC Deck Android client.</description>
  <licenses>
    <license>
      <name>MIT License</name>
      <url>https://opensource.org/license/mit</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
</project>
"@
    [IO.File]::WriteAllText($RepoPom, "$($Pom.Trim())`n")
    [IO.File]::WriteAllText($RepoSha, "$AarSha256  $ArtifactName.aar`n")
    Copy-Item -LiteralPath $ExpectedApi -Destination $RepoApi -Force
    Copy-Item -LiteralPath $PatchProvenance -Destination $RepoFrpProvenance -Force
    Copy-Item -LiteralPath $ValidatedNativeReport -Destination $RepoNative -Force
    & go run ./cmd/writebridgeprovenance `
        -versions $VersionsFile `
        -native-report $ValidatedNativeReport `
        -output $ExternalProvenance `
        -aar-sha256 $AarSha256
    Assert-NativeSuccess 'write external bridge provenance'
    Copy-Item -LiteralPath $ExternalProvenance -Destination $RepoProvenance -Force

    "AAR: $AarOutput"
    "Maven artifact: $RepoAar"
    "SHA-256: $AarSha256"
}
finally {
    Pop-Location
}
