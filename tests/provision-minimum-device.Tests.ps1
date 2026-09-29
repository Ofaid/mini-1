$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\scripts\provision-minimum-device.ps1") -LibraryOnly

$script:Passed = 0
$script:Failed = 0

function Assert-Equal {
    param($Expected, $Actual, [string]$Name)
    if ($Expected -cne $Actual) { throw "$Name expected '$Expected' but got '$Actual'." }
}

function Assert-True {
    param([bool]$Value, [string]$Name)
    if (-not $Value) { throw "$Name expected true." }
}

function Assert-ThrowsCode {
    param([scriptblock]$Action, [string]$Code, [string]$Name)
    try {
        & $Action
        throw "$Name did not throw."
    } catch {
        if ($_.Exception.Message -notmatch "^\[$([regex]::Escape($Code))\]") {
            throw "$Name threw unexpected error: $($_.Exception.Message)"
        }
    }
}

function Test-Case {
    param([string]$Name, [scriptblock]$Action)
    try {
        & $Action
        $script:Passed++
        Write-Host "PASS $Name"
    } catch {
        $script:Failed++
        Write-Host "FAIL $Name - $($_.Exception.Message)"
    }
}

function New-VerificationFixture {
    $root = Join-Path ([IO.Path]::GetTempPath()) (
        "minimum provisioning verification " + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path (Join-Path $root "scripts") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $root "minimum-foss.apk") -Value "fixture" -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $root "VERSION.txt") -Value "3.7.3-minimum.3" -Encoding ASCII
    [ordered]@{
        applicationId = "se.lublin.mumla"
        versionCode = 3070302
        versionName = "3.7.3-minimum.3"
        signerSha256 = "A" * 64
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "RELEASE-MANIFEST.json") -Encoding UTF8
    [ordered]@{
        ApplicationId = "se.lublin.mumla"
        VersionCode = 3070302
        VersionName = "3.7.3-minimum.3"
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "identity.json") -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $root "signers.txt") -Value ("A" * 64) -Encoding ASCII

    $fakeUpdater = @'
param(
    [string]$Serial = "UPDATER-SERIAL",
    [int]$TransportId = 999,
    [int]$AdbPort = 65535,
    [switch]$LibraryOnly
)
$script:CurrentTarget = "UPDATER-TARGET"
function Throw-UpdateError {
    param([string]$Code, [string]$Message)
    throw "[$Code] $Message"
}
function Read-ReleaseBundle {
    param([string]$Root)
    Add-Content -LiteralPath (Join-Path $Root "transcript.txt") -Value "READ_BUNDLE"
    if (Test-Path -LiteralPath (Join-Path $Root "read-error")) {
        Throw-UpdateError "BUNDLE_CHECKSUM" "fixture checksum failure"
    }
    $manifest = Get-Content -LiteralPath (Join-Path $Root "RELEASE-MANIFEST.json") -Raw | ConvertFrom-Json
    [pscustomobject]@{
        Root = $Root
        Manifest = $manifest
        ApkPath = (Resolve-Path -LiteralPath (Join-Path $Root "minimum-foss.apk")).Path
    }
}
function Get-ApkManifestIdentity {
    param([string]$ApkPath)
    $root = Split-Path -Parent $ApkPath
    Add-Content -LiteralPath (Join-Path $root "transcript.txt") -Value "IDENTITY"
    Get-Content -LiteralPath (Join-Path $root "identity.json") -Raw | ConvertFrom-Json
}
function Get-ApkSignerDigests {
    param([string]$ApkPath)
    $root = Split-Path -Parent $ApkPath
    Add-Content -LiteralPath (Join-Path $root "transcript.txt") -Value "SIGNERS"
    @((Get-Content -LiteralPath (Join-Path $root "signers.txt")) | Where-Object { $_ })
}
if ($LibraryOnly) { return }
throw "Fake updater must only be loaded as a library."
'@
    Set-Content -LiteralPath (Join-Path $root "scripts\update-minimum-device.ps1") `
        -Value $fakeUpdater -Encoding UTF8
    return $root
}

function Remove-VerificationFixture {
    param([string]$Root)
    if ($Root -and (Test-Path -LiteralPath $Root)) {
        Remove-Item -LiteralPath $Root -Recurse -Force
    }
}

Test-Case "release markers distinguish source and extracted bundle layouts" {
    $root = Join-Path ([IO.Path]::GetTempPath()) ("minimum-source-layout-" + [guid]::NewGuid().ToString("N"))
    try {
        New-Item -ItemType Directory -Path $root | Out-Null
        Assert-True (-not (Test-ReleaseBundleLayout -Root $root)) "empty source layout"
        Set-Content -LiteralPath (Join-Path $root "minimum-foss.apk") -Value "fixture" -Encoding ASCII
        Assert-True (Test-ReleaseBundleLayout -Root $root) "release marker"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "valid Release artifact is bound before any ADB operation" {
    $root = New-VerificationFixture
    try {
        $Serial = "KEEP-SERIAL"
        $TransportId = 7
        $AdbPort = 5041
        $script:targetRecord = "KEEP-TARGET"
        $artifact = Get-VerifiedReleaseProvisioningArtifact -Root $root
        Assert-Equal "RELEASE_MANIFEST_AND_SIGNER_VERIFIED" $artifact.Trust "release trust"
        Assert-Equal "se.lublin.mumla" $artifact.ApplicationId "package"
        Assert-Equal ([long]3070302) ([long]$artifact.VersionCode) "version code"
        Assert-Equal ((Get-FileHash -LiteralPath $artifact.ApkPath -Algorithm SHA256).Hash) `
            $artifact.ApkSha256 "bound APK hash"
        Assert-Equal ("A" * 64) $artifact.SignerSha256 "bound signer"
        Assert-Equal "KEEP-SERIAL" $Serial "provisioning Serial scope"
        Assert-Equal 7 $TransportId "provisioning TransportId scope"
        Assert-Equal 5041 $AdbPort "provisioning AdbPort scope"
        Assert-Equal "KEEP-TARGET" $script:targetRecord "provisioning target scope"
        $transcript = @(Get-Content -LiteralPath (Join-Path $root "transcript.txt"))
        Assert-Equal "READ_BUNDLE|IDENTITY|SIGNERS" ($transcript -join "|") "verification transcript"
        Assert-True (-not (($transcript -join "|") -match "ADB|INSTALL|SETPROP|RECEIVER")) "no device operation"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "Release verification propagates bundle checksum refusal" {
    $root = New-VerificationFixture
    try {
        Set-Content -LiteralPath (Join-Path $root "read-error") -Value "1" -Encoding ASCII
        Assert-ThrowsCode { Get-VerifiedReleaseProvisioningArtifact -Root $root } `
            "BUNDLE_CHECKSUM" "checksum refusal"
        $transcript = @(Get-Content -LiteralPath (Join-Path $root "transcript.txt"))
        Assert-Equal "READ_BUNDLE" ($transcript -join "|") "fail-fast transcript"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "incomplete Release layout fails closed" {
    $root = New-VerificationFixture
    try {
        Remove-Item -LiteralPath (Join-Path $root "minimum-foss.apk") -Force
        Assert-ThrowsCode { Get-VerifiedReleaseProvisioningArtifact -Root $root } `
            "BUNDLE_INCOMPLETE" "missing APK"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $root "transcript.txt"))) "verifier not executed"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "missing Release verifier fails before executing bundle code" {
    $root = New-VerificationFixture
    try {
        Remove-Item -LiteralPath (Join-Path $root "scripts\update-minimum-device.ps1") -Force
        Assert-ThrowsCode { Get-VerifiedReleaseProvisioningArtifact -Root $root } `
            "BUNDLE_VERIFIER_MISSING" "missing verifier"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $root "transcript.txt"))) "no verifier transcript"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "Release mode refuses an APK outside the manifest bundle" {
    $root = New-VerificationFixture
    try {
        $outside = Join-Path (Split-Path -Parent $root) ("outside-" + [guid]::NewGuid().ToString("N") + ".apk")
        try {
            Set-Content -LiteralPath $outside -Value "fixture" -Encoding ASCII
            Assert-ThrowsCode { Get-VerifiedReleaseProvisioningArtifact -Root $root -RequestedApkPath $outside } `
                "APK_PATH_OUTSIDE_BUNDLE" "outside APK"
            Assert-True (-not (Test-Path -LiteralPath (Join-Path $root "transcript.txt"))) "verifier not executed"
        } finally { if (Test-Path -LiteralPath $outside) { Remove-Item -LiteralPath $outside -Force } }
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "Release mode accepts the exact canonical APK path" {
    $root = New-VerificationFixture
    try {
        $requested = Join-Path $root ".\minimum-foss.apk"
        $artifact = Get-VerifiedReleaseProvisioningArtifact -Root $root -RequestedApkPath $requested
        Assert-Equal (Resolve-Path -LiteralPath (Join-Path $root "minimum-foss.apk")).Path `
            $artifact.ApkPath "canonical APK"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "APK identity mismatch is refused" {
    $root = New-VerificationFixture
    try {
        $identity = Get-Content -LiteralPath (Join-Path $root "identity.json") -Raw | ConvertFrom-Json
        $identity.VersionCode = 1
        $identity | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "identity.json") -Encoding UTF8
        Assert-ThrowsCode { Get-VerifiedReleaseProvisioningArtifact -Root $root } `
            "APK_IDENTITY_BINDING" "identity mismatch"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "missing or extra APK signers are refused" {
    $root = New-VerificationFixture
    try {
        Set-Content -LiteralPath (Join-Path $root "signers.txt") -Value @(("A" * 64), ("B" * 64)) -Encoding ASCII
        Assert-ThrowsCode { Get-VerifiedReleaseProvisioningArtifact -Root $root } `
            "APK_SIGNER_BINDING" "extra signer"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "wrong reviewed APK signer is refused" {
    $root = New-VerificationFixture
    try {
        Set-Content -LiteralPath (Join-Path $root "signers.txt") -Value ("B" * 64) -Encoding ASCII
        Assert-ThrowsCode { Get-VerifiedReleaseProvisioningArtifact -Root $root } `
            "APK_SIGNER_BINDING" "wrong signer"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "development artifact verifies signature without claiming Release trust" {
    $root = New-VerificationFixture
    try {
        $artifact = Get-VerifiedDevelopmentProvisioningArtifact -Root $root `
            -DevelopmentApkPath (Join-Path $root "minimum-foss.apk")
        Assert-Equal "DEVELOPMENT_SIGNATURE_VALID_NOT_RELEASE_BOUND" $artifact.Trust "development trust"
        Assert-True ($artifact.Trust -notmatch '^RELEASE_') "no Release claim"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "development artifact still refuses wrong package and multiple signers" {
    $root = New-VerificationFixture
    try {
        $identity = Get-Content -LiteralPath (Join-Path $root "identity.json") -Raw | ConvertFrom-Json
        $identity.ApplicationId = "example.not.minimum"
        $identity | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "identity.json") -Encoding UTF8
        Assert-ThrowsCode {
            Get-VerifiedDevelopmentProvisioningArtifact -Root $root `
                -DevelopmentApkPath (Join-Path $root "minimum-foss.apk")
        } "DEVELOPMENT_APK_IDENTITY" "wrong development package"
        $identity.ApplicationId = "se.lublin.mumla"
        $identity | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root "identity.json") -Encoding UTF8
        Set-Content -LiteralPath (Join-Path $root "signers.txt") -Value @(("A" * 64), ("B" * 64)) -Encoding ASCII
        Assert-ThrowsCode {
            Get-VerifiedDevelopmentProvisioningArtifact -Root $root `
                -DevelopmentApkPath (Join-Path $root "minimum-foss.apk")
        } "DEVELOPMENT_APK_SIGNATURE" "multiple development signers"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "final verification repeats complete Release binding when APK is unchanged" {
    $root = New-VerificationFixture
    try {
        $artifact = Get-VerifiedReleaseProvisioningArtifact -Root $root
        $confirmed = Confirm-ProvisioningArtifactUnchanged -ExpectedArtifact $artifact `
            -Root $root -ReleaseBundleMode $true
        Assert-Equal $artifact.ApkSha256 $confirmed.ApkSha256 "final hash"
        Assert-Equal $artifact.SignerSha256 $confirmed.SignerSha256 "final signer"
        Assert-Equal $artifact.Trust $confirmed.Trust "final trust"
        $transcript = @(Get-Content -LiteralPath (Join-Path $root "transcript.txt"))
        Assert-Equal "READ_BUNDLE|IDENTITY|SIGNERS|READ_BUNDLE|IDENTITY|SIGNERS" `
            ($transcript -join "|") "complete verification repeated"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "APK replacement after preflight fails before final verifier or install" {
    $root = New-VerificationFixture
    try {
        $artifact = Get-VerifiedReleaseProvisioningArtifact -Root $root
        Set-Content -LiteralPath $artifact.ApkPath -Value "tampered after operator wait" -Encoding ASCII
        Assert-ThrowsCode {
            Confirm-ProvisioningArtifactUnchanged -ExpectedArtifact $artifact `
                -Root $root -ReleaseBundleMode $true
        } "APK_CHANGED_AFTER_VERIFICATION" "post-preflight replacement"
        $transcript = @(Get-Content -LiteralPath (Join-Path $root "transcript.txt"))
        Assert-Equal "READ_BUNDLE|IDENTITY|SIGNERS" ($transcript -join "|") `
            "tamper refusal before loading verifier again"
    } finally { Remove-VerificationFixture -Root $root }
}

Test-Case "Release verification is ordered before ADB and every device mutation" {
    $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot "..\scripts\provision-minimum-device.ps1") -Raw
    $preflight = $source.IndexOf('$verifiedArtifact = Get-VerifiedReleaseProvisioningArtifact')
    $adb = $source.IndexOf('$adbPath = (Get-Command adb')
    $target = $source.IndexOf('$AdbPort = Select-AdbServerPort')
    $setprop = $source.IndexOf('"shell", "setprop", "ro.build.install"')
    $install = $source.IndexOf('Install-MinimumApk -ExpectedArtifact $verifiedArtifact')
    $finalVerification = $source.IndexOf('$finalArtifact = Confirm-ProvisioningArtifactUnchanged')
    $nativeInstall = $source.IndexOf('$output = @(& $adbPath @installArgs 2>&1)')
    Assert-True ($preflight -ge 0) "preflight call present"
    Assert-True ($preflight -lt $adb) "preflight before ADB resolution"
    Assert-True ($preflight -lt $target) "preflight before target selection"
    Assert-True ($preflight -lt $setprop) "preflight before RYKS mutation"
    Assert-True ($preflight -lt $install) "preflight before install"
    Assert-True ($finalVerification -ge 0) "final verification present inside installer"
    Assert-True ($finalVerification -lt $nativeInstall) "final verification before native adb install"
    Assert-True ($source.IndexOf('[RELEASE_BUNDLE_BUILD_REFUSED]') -lt $adb) "BuildApk refusal before ADB"
}

Test-Case "Release workflow authorizes exact reviewed main before tag checkout or secrets" {
    $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot `
        "..\.github\workflows\release-apk.yml") -Raw
    $reviewedCheckout = $source.IndexOf('name: Checkout reviewed main for release authorization')
    $authorization = $source.IndexOf('name: Bind release tag to current reviewed main')
    $exactBinding = $source.IndexOf('if [[ "$tag_sha" != "$main_sha" ]]')
    $tagCheckout = $source.IndexOf('name: Checkout authorized release commit with Humla')
    $secretAccess = $source.IndexOf('${{ secrets.MINIMUM_RELEASE_KEYSTORE_BASE64 }}')
    Assert-True ($reviewedCheckout -ge 0) "reviewed main checkout present"
    Assert-True ($reviewedCheckout -lt $authorization) "reviewed main checked out before authorization"
    Assert-True ($authorization -lt $exactBinding) "authorization step contains exact-SHA binding"
    Assert-True ($exactBinding -lt $tagCheckout) "exact binding before tag checkout"
    Assert-True ($tagCheckout -lt $secretAccess) "authorized checkout before signing secret access"
    Assert-True ($source -match 'git checkout --detach "\$RELEASE_SHA"') "checkout uses authorized SHA"
}

Write-Host "Provisioning verification tests: $($script:Passed) passed, $($script:Failed) failed"
if ($script:Failed -gt 0) { exit 1 }
