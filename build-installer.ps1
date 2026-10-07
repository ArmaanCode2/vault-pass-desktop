<#
.SYNOPSIS
    Builds the VaultPass Windows installer and, with a signing key, signs it for the in-app updater.

.DESCRIPTION
    0. Stops with exit code 4 when version.txt isn't x.y.z. Stops with exit code 3 when a signed
       installer (VaultPass-Setup-v<x.y.z>.exe.sig, checked by name only) for that version or a higher
       one is already in dist\ or ..\launch\, or when version.txt isn't higher than every release tag
       (x.y.z or vx.y.z) on GitHub (ArmaanCode2/vault-pass-desktop). If GitHub can't be asked
       (no network, rate limit), it warns and continues.
    1. Finds a JDK 21 (JAVA_VERSION "21.*" in its release file) that has jpackage.exe and uses it for
       Gradle (JAVA_HOME + PATH) and for the signer.
    2. With -SigningKeystore (or the VAULTPASS_UPDATE_KEYSTORE environment variable), checks that the
       keystore file exists before anything is built.
    3. Generates assets\logo.ico if it is missing.
    4. Deletes old dist\VaultPass-Setup-*.exe files and their .sig/.sha256 files.
    5. Builds the app image (gradlew createDistributable --no-daemon).
    6. Compiles installer\vaultpass.iss with Inno Setup into dist\VaultPass-Setup-v<version>.exe.
    7. With a keystore, runs tools\SignInstaller.java with that JDK's java.exe. The signer asks for the
       keystore password at a hidden console prompt and writes VaultPass-Setup-v<version>.exe.sig and
       .sha256 next to the installer. The key is decrypted only inside that one java process.
    8. Prints the installer path, size and SHA256.

    Installed copies of VaultPass only install updates whose .sig verifies with the public key built
    into them (src\main\resources\update-signing-key.pem). See RELEASING.md.

.EXAMPLE
    .\build-installer.ps1 -SigningKeystore D:\keys\vaultpass-desktop-update.p12
#>
param(
    # PKCS12 file holding the EC P-256 update signing key (alias "update").
    [string]$SigningKeystore = $env:VAULTPASS_UPDATE_KEYSTORE,
    # Full path to ISCC.exe when Inno Setup 6 is not in a standard location.
    [string]$InnoSetupCompiler,
    # Pass --offline to Gradle (all dependencies already downloaded).
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
# @(...) -join '' turns an empty file into '' instead of $null; Get-Content handles a BOM.
$version = (@(Get-Content -Raw -LiteralPath (Join-Path $projectDir 'version.txt')) -join '').Trim()

# Compares version.txt with GitHub release tags. Only tags like 1.2.3 or v1.2.3 count; prereleases
# (v1.2.0-beta) and other tags are ignored. Status is Invalid, NotNewer or Newer.
function Test-VersionIsNewer {
    param([string]$Version, [string[]]$Tags)
    $current = $null
    if (-not ($Version -match '^\d+\.\d+\.\d+$') -or -not [version]::TryParse($Version, [ref]$current)) {
        return [pscustomobject]@{
            Status = 'Invalid'; IsNewer = $false; Latest = $null
            Message = "version.txt says '$Version', which isn't a version number like 1.2.3. Fix version.txt, then run again."
        }
    }
    $latest = $null
    $latestTag = $null
    foreach ($tag in @($Tags)) {
        if (-not ($tag -match '^v?\d+\.\d+\.\d+$')) { continue }
        $parsed = $null
        if (-not [version]::TryParse(($tag -replace '^v', ''), [ref]$parsed)) { continue }
        if ($null -eq $latest -or $parsed -gt $latest) {
            $latest = $parsed
            $latestTag = $tag
        }
    }
    if ($null -eq $latest) {
        return [pscustomobject]@{
            Status = 'Newer'; IsNewer = $true; Latest = $null
            Message = "version.txt says $Version; GitHub has no release with a version tag yet."
        }
    }
    if ($current -le $latest) {
        return [pscustomobject]@{
            Status = 'NotNewer'; IsNewer = $false; Latest = $latestTag
            Message = "version.txt says $Version but GitHub already has $latestTag. Raise the number in version.txt, then run again."
        }
    }
    return [pscustomobject]@{
        Status = 'Newer'; IsNewer = $true; Latest = $latestTag
        Message = "version.txt says $Version; the newest release on GitHub is $latestTag."
    }
}

# Looks in each folder for VaultPass-Setup-v<x.y.z>.exe.sig files, by name only (they are not opened),
# and returns the highest version that is equal to or higher than $Version as Version/Name/Folder, or $null.
function Find-SignedInstaller {
    param([string]$Version, [string[]]$Folders)
    $current = [version]$Version
    $best = $null
    foreach ($folder in @($Folders)) {
        if (-not $folder -or -not (Test-Path -LiteralPath $folder -PathType Container)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $folder -Filter 'VaultPass-Setup-v*.exe.sig' -File -ErrorAction SilentlyContinue)) {
            if (-not ($file.Name -match '^VaultPass-Setup-v(\d+\.\d+\.\d+)\.exe\.sig$')) { continue }
            $name = $Matches[1]
            $found = $null
            if (-not [version]::TryParse($name, [ref]$found)) { continue }
            if ($found -lt $current) { continue }
            if ($null -eq $best -or $found -gt $best.Version) {
                $best = [pscustomobject]@{ Version = $found; Name = $name; Folder = $folder }
            }
        }
    }
    return $best
}

# Reads the release tags of a public GitHub repo (no token). Status is Ok (Tags filled), NotFound (404)
# or Error (Detail says why).
function Get-GitHubReleaseTags {
    param([string]$Repo)
    $ProgressPreference = 'SilentlyContinue'
    # Windows PowerShell 5.1 doesn't always offer TLS 1.2, which GitHub requires.
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $uri = "https://api.github.com/repos/$Repo/releases?per_page=100"
    $headers = @{ 'User-Agent' = 'VaultPass-Release-Script'; 'Accept' = 'application/vnd.github+json' }
    try {
        $response = Invoke-WebRequest -Uri $uri -Headers $headers -UseBasicParsing -TimeoutSec 10
    } catch {
        $statusCode = $null
        $errorResponse = $_.Exception.Response
        if ($errorResponse -and $errorResponse.StatusCode) { $statusCode = [int]$errorResponse.StatusCode }
        if ($statusCode -eq 404) {
            return [pscustomobject]@{ Status = 'NotFound'; Tags = @(); Detail = 'HTTP 404' }
        }
        if ($statusCode -eq 403) {
            return [pscustomobject]@{ Status = 'Error'; Tags = @(); Detail = 'HTTP 403, probably the GitHub rate limit' }
        }
        if ($statusCode) {
            return [pscustomobject]@{ Status = 'Error'; Tags = @(); Detail = "HTTP $statusCode" }
        }
        return [pscustomobject]@{ Status = 'Error'; Tags = @(); Detail = $_.Exception.Message }
    }
    $body = $response.Content
    if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
    $body = ([string]$body).Trim()
    if (-not $body.StartsWith('[')) {
        return [pscustomobject]@{ Status = 'Error'; Tags = @(); Detail = 'GitHub did not answer with a list of releases' }
    }
    try {
        $releases = $body | ConvertFrom-Json
    } catch {
        return [pscustomobject]@{ Status = 'Error'; Tags = @(); Detail = "GitHub's answer couldn't be read: $($_.Exception.Message)" }
    }
    $tags = @()
    foreach ($release in $releases) {
        if ($release -and $release.tag_name) { $tags += [string]$release.tag_name }
    }
    return [pscustomobject]@{ Status = 'Ok'; Tags = $tags; Detail = "HTTP $([int]$response.StatusCode)" }
}

# 0. version.txt must be x.y.z (exit code 4), and higher than every signed installer already built here
# and every release on GitHub (exit code 3), checked before anything is deleted or built. If GitHub
# can't be asked, warn and continue.
$releaseRepo = 'ArmaanCode2/vault-pass-desktop'
$versionCheck = Test-VersionIsNewer -Version $version -Tags @()
if ($versionCheck.Status -eq 'Invalid') {
    Write-Host $versionCheck.Message -ForegroundColor Red
    exit 4
}
$signedFolders = @((Join-Path $projectDir 'dist'), (Join-Path (Split-Path $projectDir -Parent) 'launch'))
$signedInstaller = Find-SignedInstaller -Version $version -Folders $signedFolders
if ($signedInstaller) {
    $found = $signedInstaller.Name
    $folder = $signedInstaller.Folder
    Write-Host "A signed installer for $found already exists in $folder. Raise the number in version.txt. If you never published or installed $found, you can instead delete its .exe, .sig and .sha256 from $folder, then run again." -ForegroundColor Red
    exit 3
}
$releaseTags = Get-GitHubReleaseTags -Repo $releaseRepo
if ($releaseTags.Status -eq 'Ok') {
    $versionCheck = Test-VersionIsNewer -Version $version -Tags $releaseTags.Tags
    if (-not $versionCheck.IsNewer) {
        Write-Host $versionCheck.Message -ForegroundColor Red
        exit 3
    }
    Write-Host $versionCheck.Message
} elseif ($releaseTags.Status -eq 'NotFound') {
    Write-Host "GitHub has no releases for $releaseRepo (HTTP 404); continuing."
} else {
    Write-Warning "Couldn't check GitHub for the latest version ($($releaseTags.Detail)). Continuing; make sure version.txt is higher than the newest release before publishing."
}

$installerName = "VaultPass-Setup-v$version.exe"
$distDir = Join-Path $projectDir 'dist'
$installerPath = Join-Path $distDir $installerName
$gradlew = Join-Path $projectDir 'gradlew.bat'
$signer = Join-Path $projectDir 'tools\SignInstaller.java'
$embeddedKey = Join-Path $projectDir 'src\main\resources\update-signing-key.pem'
$gradleExtraArgs = @()
if ($Offline) { $gradleExtraArgs += '--offline' }

# Returns the JDK's version from JAVA_VERSION in its release file when it is 21.x, otherwise $null.
function Get-Jdk21Version {
    param([string]$JdkHome)
    $release = Join-Path $JdkHome 'release'
    if (-not (Test-Path -LiteralPath $release -PathType Leaf)) { return $null }
    $line = Get-Content -LiteralPath $release | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
    if (-not $line) { return $null }
    $value = ($line -replace '^JAVA_VERSION=', '').Trim().Trim('"')
    if (-not ($value -match '^21(\.\d+){0,3}(?!\d)')) { return $null }
    $numeric = $Matches[0]
    if ($numeric -notmatch '\.') { $numeric += '.0' }
    return [version]$numeric
}

function Test-UsableJdk {
    param([string]$JdkHome)
    return (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\jpackage.exe')) -and
        (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe'))
}

function Find-Jdk21 {
    # JAVA_HOME counts only if it is a JDK 21 that can package the app (Android Studio's JBR has no jpackage).
    if ($env:JAVA_HOME -and (Test-UsableJdk $env:JAVA_HOME) -and (Get-Jdk21Version $env:JAVA_HOME)) {
        return $env:JAVA_HOME
    }
    $patterns = @(
        'C:\Program Files\Microsoft\jdk-*',
        'C:\Program Files\Eclipse Adoptium\jdk-*',
        'C:\Program Files\Java\jdk-*'
    )
    $found = @()
    foreach ($pattern in $patterns) {
        foreach ($jdk in (Get-Item -Path $pattern -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer })) {
            $jdkVersion = Get-Jdk21Version $jdk.FullName
            if ($jdkVersion -and (Test-UsableJdk $jdk.FullName)) {
                $found += [pscustomobject]@{ Path = $jdk.FullName; Version = $jdkVersion }
            }
        }
    }
    # Highest version by [version] comparison, not by folder name.
    $best = $found | Sort-Object -Property Version -Descending | Select-Object -First 1
    if ($best) { return $best.Path }
    throw 'No JDK 21 with jpackage.exe was found. Install a JDK 21 (for example Microsoft Build of OpenJDK) or set JAVA_HOME to one.'
}

function Find-InnoSetupCompiler {
    param([string]$Explicit)
    if ($Explicit) {
        if (Test-Path -LiteralPath $Explicit) { return $Explicit }
        throw "ISCC.exe not found at $Explicit"
    }
    $onPath = Get-Command 'ISCC.exe' -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $candidates = @(
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe')
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    throw 'Inno Setup 6 (ISCC.exe) was not found. Install it or pass -InnoSetupCompiler <path to ISCC.exe>.'
}

function Write-InstallerSummary {
    $item = Get-Item -LiteralPath $installerPath
    $sizeMb = [Math]::Round($item.Length / 1MB, 1)
    $hash = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash
    Write-Host ''
    Write-Host "Installer: $installerPath" -ForegroundColor Green
    Write-Host "Size:      $sizeMb MB"
    Write-Host "SHA256:    $hash"
}

Write-Host "Building VaultPass $version" -ForegroundColor Cyan

# 1. JDK 21 with jpackage for Gradle, this session's child processes and the signer.
$jdkHome = Find-Jdk21
$env:JAVA_HOME = $jdkHome
$env:PATH = (Join-Path $jdkHome 'bin') + ';' + $env:PATH
$javaExe = Join-Path $jdkHome 'bin\java.exe'
Write-Host "Using JDK: $jdkHome"

# 2. Keystore, checked before the build so a typo doesn't cost a full build.
$keystorePath = $null
if ($SigningKeystore) {
    if (-not (Test-Path -LiteralPath $SigningKeystore -PathType Leaf)) {
        throw "Signing keystore not found: $SigningKeystore"
    }
    $keystorePath = (Resolve-Path -LiteralPath $SigningKeystore).Path
    if (-not (Test-Path -LiteralPath $signer -PathType Leaf)) { throw "Signer not found: $signer" }
    Write-Host "Signing keystore: $keystorePath"
}

if (-not (Test-Path -LiteralPath $embeddedKey)) {
    Write-Warning 'This build has no update signing key (src\main\resources\update-signing-key.pem). The app it produces will refuse every update. See RELEASING.md for the one-time key step.'
}

# 3. Application icon.
if (-not (Test-Path -LiteralPath (Join-Path $projectDir 'assets\logo.ico'))) {
    Write-Host 'assets\logo.ico is missing; generating it.'
    & (Join-Path $projectDir 'installer\generate-icon.ps1')
}

# 4. Old installers and their signature files.
if (Test-Path -LiteralPath $distDir) {
    Get-ChildItem -LiteralPath $distDir -File |
        Where-Object { $_.Name -like 'VaultPass-Setup-*.exe' -or $_.Name -like 'VaultPass-Setup-*.exe.sig' -or $_.Name -like 'VaultPass-Setup-*.exe.sha256' } |
        ForEach-Object {
            Write-Host "Removing old $($_.Name)"
            Remove-Item -LiteralPath $_.FullName -Force
        }
}

# 5. App image used by the installer (build\compose\binaries\main\app\VaultPass).
# -p: Gradle otherwise builds whatever project the current directory belongs to.
& $gradlew -p $projectDir createDistributable --no-daemon @gradleExtraArgs
if ($LASTEXITCODE -ne 0) { throw "gradlew createDistributable failed (exit code $LASTEXITCODE)." }

# 6. Inno Setup installer.
$iscc = Find-InnoSetupCompiler -Explicit $InnoSetupCompiler
& $iscc "/DMyAppVersion=$version" (Join-Path $projectDir 'installer\vaultpass.iss')
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed (exit code $LASTEXITCODE)." }
if (-not (Test-Path -LiteralPath $installerPath)) { throw "Inno Setup finished but $installerPath is missing." }

# 7. Update signature.
if (-not $keystorePath) {
    Write-InstallerSummary
    Write-Host ''
    Write-Warning "UNSIGNED RELEASE: $installerName has no .sig file. Installed copies of VaultPass will refuse to update to it. Run again with -SigningKeystore <path to .p12> before publishing."
    exit 0
}

# The signer reads the password itself from a hidden console prompt; it never passes through this script.
& $javaExe $signer --keystore $keystorePath --installer $installerPath --version $version --project-dir $projectDir
$signExitCode = $LASTEXITCODE

if ($signExitCode -eq 2) {
    Write-InstallerSummary
    Write-Host ''
    Write-Warning "The update public key was just written to src\main\resources\update-signing-key.pem and nothing was signed. Don't publish $installerName : it was built without the key. Record the public key SHA-256 printed above, commit the .pem file, then run this script again so the app is rebuilt with the key inside and signed."
    exit 2
}
if ($signExitCode -ne 0) {
    Write-InstallerSummary
    Write-Host ''
    Write-Warning "Signing failed (exit code $signExitCode). $installerName is NOT signed; don't publish it."
    exit $signExitCode
}

# 8. Summary.
Write-InstallerSummary
Write-Host ''
Write-Host "Upload these three files to the GitHub release tagged v${version}:" -ForegroundColor Green
Write-Host "  $installerPath"
Write-Host "  $installerPath.sig"
Write-Host "  $installerPath.sha256"
