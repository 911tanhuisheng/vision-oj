# Run only after the Ubuntu Sandbox has been updated and passed smoke_test.py.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sandboxEnvPath = Join-Path $projectRoot 'Sandbox/.sandbox.env'
$backendLocalPath = Join-Path $projectRoot 'spingboot-init/src/main/resources/application-local.yml'

if (-not (Test-Path -LiteralPath $sandboxEnvPath)) {
    throw '缺少 Sandbox/.sandbox.env，请先准备 Ubuntu 与主后端共用的密钥。'
}
$keyLine = Get-Content -LiteralPath $sandboxEnvPath | Where-Object { $_ -match '^SANDBOX_AUTH_KEY=' } | Select-Object -First 1
if (-not $keyLine) {
    throw 'Sandbox/.sandbox.env 缺少 SANDBOX_AUTH_KEY。'
}
$sandboxKey = $keyLine.Substring('SANDBOX_AUTH_KEY='.Length).Trim().Trim('"').Trim("'")
if ($sandboxKey.Length -lt 32 -or $sandboxKey -eq '123456' -or $sandboxKey.StartsWith('replace-with-')) {
    throw 'SANDBOX_AUTH_KEY 必须是至少 32 位的随机密钥。'
}

if (Test-Path -LiteralPath $backendLocalPath) {
    $lines = @(Get-Content -LiteralPath $backendLocalPath)
    $insideCodesandbox = $false
    $replaced = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^codesandbox:\s*$') {
            $insideCodesandbox = $true
            continue
        }
        if ($insideCodesandbox -and $lines[$i] -match '^[^\s#]') {
            $insideCodesandbox = $false
        }
        if ($insideCodesandbox -and $lines[$i] -match '^  auth-key:\s*') {
            $lines[$i] = "  auth-key: $sandboxKey"
            $replaced = $true
            break
        }
    }
    if (-not $replaced) {
        throw 'application-local.yml 已存在但没有 codesandbox.auth-key，请手动检查，脚本未覆盖其他配置。'
    }
    $updated = ($lines -join "`n") + "`n"
} else {
    $updated = "codesandbox:`n  auth-key: $sandboxKey`n"
}
[System.IO.File]::WriteAllText($backendLocalPath, $updated, [System.Text.Encoding]::UTF8)
Write-Host '主后端本地密钥已更新；请重启主后端。密钥未输出，也不会提交到 Git。'
