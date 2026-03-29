# PoC: 성공/실패 API 시나리오 자동 검증
# 사용법: 서버가 없으면 자동 기동(test-flow). 이미 8080이 떠 있으면 그대로 사용.
#   .\scripts\verify-scenarios.ps1
#   .\scripts\verify-scenarios.ps1 -SkipServer   # 서버 직접 띄운 경우

param(
    [switch] $SkipServer
)

$ErrorActionPreference = "Stop"
# 스크립트 위치: biometric-auth-server/scripts/
$serverRoot = Split-Path $PSScriptRoot -Parent
$utf8 = New-Object System.Text.UTF8Encoding $false

function Write-Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red }

function Wait-Port8080 {
    for ($i = 0; $i -lt 60; $i++) {
        if ((Test-NetConnection -ComputerName localhost -Port 8080 -WarningAction SilentlyContinue).TcpTestSucceeded) {
            return $true
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Invoke-CurlJson {
    param([string]$Method, [string]$Url, [string]$JsonPath = $null)
    $args = @("-s", "-w", "`nHTTPSTATUS:%{http_code}", "-X", $Method, $Url, "-H", "Content-Type: application/json")
    if ($JsonPath) {
        $args += "--data-binary", "@$JsonPath"
    }
    $raw = (& curl.exe @args) -join "`n"
    if ($raw -match 'HTTPSTATUS:(\d+)\s*$') {
        $code = [int]$Matches[1]
        $body = ($raw -replace 'HTTPSTATUS:\d+\s*$', '').Trim()
    } else {
        $code = 0
        $body = $raw.Trim()
    }
    return @{ Body = $body; Code = $code }
}

if (-not $SkipServer) {
    $p = (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
    if ($p) {
        Write-Host "포트 8080 사용 중 — 기존 서버를 사용합니다." -ForegroundColor Yellow
    } else {
        Write-Host "서버 기동 중: gradlew :biometric-auth-app:bootRun (test-flow)..." -ForegroundColor Yellow
        Set-Location $serverRoot
        Start-Process -FilePath ".\gradlew.bat" -ArgumentList ":biometric-auth-app:bootRun", "--args=--spring.profiles.active=test-flow" -WorkingDirectory $serverRoot -WindowStyle Hidden
        if (-not (Wait-Port8080)) {
            Write-Fail "8080 대기 시간 초과"
            exit 1
        }
        Write-Ok "서버 준비됨 (http://localhost:8080)"
    }
}

Set-Location $serverRoot
$tmp = $serverRoot

# --- 성공 시나리오 ---
Write-Step "성공 시나리오"

# S1: test-flow 전체 (EC 서명 + JWT)
[System.IO.File]::WriteAllText("$tmp\flow_ok.json", '{"device_id":"verifyFlowDevice","user_id":"USER01"}', $utf8)
$r = Invoke-CurlJson -Method "POST" -Url "http://localhost:8080/test/full-flow" -JsonPath "$tmp\flow_ok.json"
if ($r.Code -eq 200 -and $r.Body -match '"verification":"SUCCESS"') {
    Write-Ok "S1 POST /test/full-flow → 200, verification=SUCCESS"
} else {
    Write-Fail "S1 기대: HTTP 200 + SUCCESS. 실제: HTTP $($r.Code) $($r.Body)"
}

# S2: 디바이스 등록
$devOk = "verifyDevOk_" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
$regBody = @"
{
  "device_id":   "$devOk",
  "user_id":     "USER01",
  "public_key":  "ZHVtbXk=",
  "enrolled_at": "2026-01-01T00:00:00Z"
}
"@
[System.IO.File]::WriteAllText("$tmp\reg_ok.json", $regBody.Trim(), $utf8)
$r = Invoke-CurlJson -Method "POST" -Url "http://localhost:8080/api/device/register" -JsonPath "$tmp\reg_ok.json"
if ($r.Code -eq 200 -and $r.Body -match "REGISTERED") {
    Write-Ok "S2 POST /api/device/register → 200 REGISTERED (device=$devOk)"
} else {
    Write-Fail "S2 HTTP $($r.Code) $($r.Body)"
}

# S3: challenge
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$chBody = @"
{
  "device_id":    "$devOk",
  "user_id":      "USER01",
  "client_nonce": "aabbccddeeff00112233445566778899",
  "timestamp":    $ts
}
"@
[System.IO.File]::WriteAllText("$tmp\ch_ok.json", $chBody.Trim(), $utf8)
$r = Invoke-CurlJson -Method "POST" -Url "http://localhost:8080/api/auth/challenge" -JsonPath "$tmp\ch_ok.json"
$sessionId = $null
if ($r.Code -eq 200) {
    try {
        $j = $r.Body | ConvertFrom-Json
        $sessionId = $j.session_id
    } catch {}
}
if ($r.Code -eq 200 -and $sessionId) {
    Write-Ok "S3 POST /api/auth/challenge → 200, session_id 수신"
} else {
    Write-Fail "S3 HTTP $($r.Code) $($r.Body)"
}

# --- 실패 시나리오 ---
Write-Step "실패 시나리오"

# F1: 잘못된 JSON 본문 (파싱 불가)
$raw = (curl.exe -s -w "`nHTTPSTATUS:%{http_code}" -X POST "http://localhost:8080/api/auth/challenge" -H "Content-Type: application/json" -d "{not-json") -join "`n"
if ($raw -match 'HTTPSTATUS:(\d+)\s*$') { $codeF1 = [int]$Matches[1]; $bodyF1 = ($raw -replace 'HTTPSTATUS:\d+\s*$', '').Trim() } else { $codeF1 = 0; $bodyF1 = $raw }
if ($codeF1 -eq 400 -and $bodyF1 -match "INVALID_REQUEST_BODY") {
    Write-Ok "F1 잘못된 JSON challenge → 400 INVALID_REQUEST_BODY"
} else {
    Write-Fail "F1 기대 HTTP 400 INVALID_REQUEST_BODY. 실제: HTTP $codeF1 $bodyF1"
}

# F2: 필수 필드 누락 (device_id 없음)
$badCh = '{"user_id":"USER01","client_nonce":"n","timestamp":1}'
[System.IO.File]::WriteAllText("$tmp\ch_bad.json", $badCh, $utf8)
$r = Invoke-CurlJson -Method "POST" -Url "http://localhost:8080/api/auth/challenge" -JsonPath "$tmp\ch_bad.json"
if ($r.Code -eq 400 -and $r.Body -match "INVALID_DEVICE_ID") {
    Write-Ok "F2 challenge device_id 누락 → 400 INVALID_DEVICE_ID"
} else {
    Write-Fail "F2 HTTP $($r.Code) $($r.Body)"
}

# F3: 존재하지 않는 디바이스
$ts2 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$nfBody = @"
{
  "device_id":    "no-such-device-99999",
  "user_id":      "USER01",
  "client_nonce": "0123456789abcdef0123456789abcdef",
  "timestamp":    $ts2
}
"@
[System.IO.File]::WriteAllText("$tmp\ch_nf.json", $nfBody.Trim(), $utf8)
$r = Invoke-CurlJson -Method "POST" -Url "http://localhost:8080/api/auth/challenge" -JsonPath "$tmp\ch_nf.json"
if ($r.Code -eq 404 -and $r.Body -match "DEVICE_NOT_FOUND") {
    Write-Ok "F3 미등록 device challenge → 404 DEVICE_NOT_FOUND"
} else {
    Write-Fail "F3 HTTP $($r.Code) $($r.Body)"
}

# F4: dummy 서명 token (S3 세션 사용)
if ($sessionId) {
    $tokBody = @"
{
  "session_id":   "$sessionId",
  "device_id":    "$devOk",
  "user_id":      "USER01",
  "ec_signature": "dummy",
  "client_nonce": "aabbccddeeff00112233445566778899",
  "timestamp":    $ts
}
"@
    [System.IO.File]::WriteAllText("$tmp\tok_bad.json", $tokBody.Trim(), $utf8)
    $r = Invoke-CurlJson -Method "POST" -Url "http://localhost:8080/api/auth/token" -JsonPath "$tmp\tok_bad.json"
    if ($r.Code -eq 401 -and $r.Body -match "INVALID_SIGNATURE") {
        Write-Ok "F4 dummy 서명 token → 401 INVALID_SIGNATURE"
    } else {
        Write-Fail "F4 HTTP $($r.Code) $($r.Body)"
    }
} else {
    Write-Fail "F4 스킵 (S3에서 session_id 없음)"
}

# F5: enrolled_at 형식 오류
$badReg = @"
{
  "device_id":   "badDateDev",
  "user_id":     "USER01",
  "public_key":  "ZHVtbXk=",
  "enrolled_at": "not-a-date"
}
"@
[System.IO.File]::WriteAllText("$tmp\reg_bad.json", $badReg.Trim(), $utf8)
$r = Invoke-CurlJson -Method "POST" -Url "http://localhost:8080/api/device/register" -JsonPath "$tmp\reg_bad.json"
if ($r.Code -eq 400 -and $r.Body -match "INVALID_ENROLLED_AT") {
    Write-Ok "F5 잘못된 enrolled_at → 400 INVALID_ENROLLED_AT"
} else {
    Write-Fail "F5 HTTP $($r.Code) $($r.Body)"
}

Write-Host "`n완료. H2: http://localhost:8080/h2-console (jdbc:h2:mem:biometric / sa / 빈 비밀번호)" -ForegroundColor DarkGray
