[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$preparePath = Join-Path $repoRoot "scripts\prepare-t99.ps1"
$prepareSource = Get-Content -LiteralPath $preparePath -Raw
$receiverPath = Join-Path $PSScriptRoot `
        "app\src\main\java\dev\minimum\wifiprovisioner\WifiProvisionReceiver.java"
$receiverSource = Get-Content -LiteralPath $receiverPath -Raw
$manifestSource = Get-Content -LiteralPath (
        Join-Path $PSScriptRoot "app\src\main\AndroidManifest.xml") -Raw

foreach ($snippet in @(
        '[guid]::NewGuid().ToString("N")',
        '$WifiHelperOperationIdExtra = "operationId"',
        'Convert-WifiHelperStatusMarker',
        '-ExpectedOperationId $operationId',
        '(?im)^\s*Success\s*$',
        '"shell", "pm", "list", "packages", $WifiHelperPackage',
        'Assert-WifiRemoteRequestAbsent -RemotePath $remoteRequest',
        'function Invoke-WifiHelperBroadcast',
        '$ErrorActionPreference = "Continue"',
        '2>&1',
        '$_.ToString()')) {
    if (-not $prepareSource.Contains($snippet)) {
        throw "prepare-t99.ps1 is missing required protocol/cleanup pattern: $snippet"
    }
}
if ($prepareSource -match '(?i)Write-(Host|Output|Warning).*?(importCall|statusCall|combinedOutput)' -or
        $prepareSource -match '(?i)(psk|password).*Write-(Host|Output|Warning)') {
    throw "Helper output or credential material must not be logged."
}

$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $preparePath, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) {
    throw "prepare-t99.ps1 has PowerShell parse errors: $($errors[0].Message)"
}
foreach ($name in @("Convert-WifiHelperStatusMarker", "Convert-WifiHelperBroadcastOutput")) {
    $function = $ast.Find({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -ceq $name
        }, $true)
    if ($null -eq $function) { throw "Missing parser function '$name'." }
    Invoke-Expression $function.Extent.Text
}

$provisioning = [regex]::Match(
        $prepareSource,
        '(?s)function Invoke-LabWifiProvisioning\s*\{.*?(?=\r?\n\$manufacturer\b)').Value
if (-not $provisioning) { throw "Missing Wi-Fi provisioning state machine." }
$proofIndex = $provisioning.IndexOf('if ($null -eq $importProof)', [StringComparison]::Ordinal)
$removeIndex = $provisioning.IndexOf('@("shell", "rm", "-f", $remoteRequest)', [StringComparison]::Ordinal)
$terminalIndex = $provisioning.IndexOf('$deadline = (Get-Date).AddSeconds(25)', [StringComparison]::Ordinal)
if ($proofIndex -lt 0 -or $removeIndex -le $proofIndex -or $terminalIndex -le $removeIndex) {
    throw "Nonce-bound import proof must precede host deletion and terminal polling."
}
if ($provisioning.Contains('Convert-WifiHelperImportAcknowledgement') -or
        $provisioning.Contains('$receiverDeleted')) {
    throw "Legacy textual ACK/receiver-owned source deletion remains in the state machine."
}

foreach ($snippet in @(
        'Pattern.compile("^[0-9a-f]{32}$")',
        'String expectedName = "minimum-wifi-" + operationId + ".json"',
        'writeBytes(new File(context.getFilesDir(), REQUEST_FILE), request);',
        'writeStatus(context, operationId, STATE_IMPORTED, null);',
        'output.getFD().sync();')) {
    if (-not $receiverSource.Contains($snippet)) {
        throw "Receiver is missing required nonce/private-state pattern: $snippet"
    }
}
if (-not $manifestSource.Contains('android:permission="android.permission.DUMP"') -or
        -not $manifestSource.Contains('android:exported="false"')) {
    throw "Manifest must protect the exported receiver with DUMP and keep the activity private."
}
$privateWrite = $receiverSource.IndexOf(
        'writeBytes(new File(context.getFilesDir(), REQUEST_FILE), request);',
        [StringComparison]::Ordinal)
$statusWrite = $receiverSource.IndexOf(
        'writeStatus(context, operationId, STATE_IMPORTED, null);',
        [StringComparison]::Ordinal)
if ($privateWrite -lt 0 -or $statusWrite -le $privateWrite) {
    throw "Receiver must fsync its private request before publishing IMPORTED."
}
if ($receiverSource -match '(?i)Log\.(d|i|w|e).*?(psk|password)' -or
        $receiverSource.Contains('source.delete()')) {
    throw "Receiver must not log credentials or try to delete the shell-owned source."
}

function Assert-Fixture([string]$Name, [scriptblock]$Action, [scriptblock]$Expectation) {
    $value = & $Action
    if (-not (& $Expectation $value)) { throw "Parser fixture failed: $Name" }
    Write-Host "Parser fixture passed: $Name"
}

$nonce = '0123456789abcdef0123456789abcdef'
$other = 'fedcba9876543210fedcba9876543210'
Assert-Fixture "quoted imported" {
    Convert-WifiHelperStatusMarker -ExpectedOperationId $nonce -Output @(
        "Broadcast completed: result=-1, data=`"IMPORTED:$nonce`"")
} { param($v) $v.State -ceq 'IMPORTED' -and $null -eq $v.Error }
Assert-Fixture "unquoted success" {
    Convert-WifiHelperStatusMarker -ExpectedOperationId $nonce -Output @(
        "Broadcast completed: result=-1, data=SUCCESS:$nonce")
} { param($v) $v.State -ceq 'SUCCESS' }
Assert-Fixture "sanitized error" {
    Convert-WifiHelperStatusMarker -ExpectedOperationId $nonce -Output @(
        "Broadcast completed: result=-1, data=`"ERROR:${nonce}:invalid-request`"")
} { param($v) $v.State -ceq 'ERROR' -and $v.Error -ceq 'invalid-request' }
foreach ($fixture in @(
        "Broadcast completed: result=0, data=`"SUCCESS:$nonce`"",
        "Broadcast completed: result=-1, data=`"SUCCESS:$other`"",
        "Broadcast completed: result=-1, data=`"SUCCESS:$nonce`:extra`"",
        "SUCCESS:$nonce",
        "diagnostic SUCCESS:$nonce",
        "", " ")) {
    Assert-Fixture "reject malformed/wrong marker" {
        Convert-WifiHelperStatusMarker -ExpectedOperationId $nonce -Output @($fixture)
    } { param($v) $null -eq $v }
}
Assert-Fixture "ErrorRecord normalization without parser bypass" {
    try { throw "native permission denied" } catch { $record = $_ }
    $normalized = @(Convert-WifiHelperBroadcastOutput -Output @(
        $record, "Broadcast completed: result=-1, data=`"SUCCESS:$nonce`""))
    [pscustomobject]@{
        Normalized = $normalized[0] -is [string]
        Parsed = Convert-WifiHelperStatusMarker -ExpectedOperationId $nonce -Output $normalized
    }
} { param($v) $v.Normalized -and $v.Parsed.State -ceq 'SUCCESS' }

Write-Host "All nonce-bound Wi-Fi helper protocol checks passed."
