# YPJ-118(spec 0087):yangzhou 应用容器一键 build/up/down/status
# 用法: .\script\app-infra.ps1 <build|up|down|status> <prod|dev|test>
# 前置:postgres/rustfs 外部实例已起(F:/code/docker-compose/postgresql 与 script/dev-infra.ps1)
# 端口格局:prod=3000+8080 / dev=3001+8081 / test=8082(仅 api)
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("build", "up", "down", "status")]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [ValidateSet("prod", "dev", "test")]
    [string]$Env
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$composeFile = Join-Path $repoRoot "infra\compose\docker-compose.$Env.yaml"

# build 阶段暴露的 host 端口(占用预检用)
$ports = switch ($Env) {
    "prod" { 3000, 8080 }
    "dev"  { 3001, 8081 }
    "test" { 8082 }
}

function Assert-PortsFree {
    # docker infra 持有 = 自家容器在发布端口(wslrelay/com.docker.backend),up -d 幂等,放行
    $dockerProcs = @('com.docker.backend', 'wslrelay', 'wslhost', 'vpnkit')
    foreach ($p in $ports) {
        $conn = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
        if ($conn) {
            $proc = (Get-CimInstance Win32_Process -Filter "ProcessId=$($conn[0].OwningProcess)").ProcessName
            if ($dockerProcs -contains $proc) { continue }
            throw "端口 $p 已被占用(PID $($conn[0].OwningProcess) / $proc)。请先停对应进程(严禁 taskkill /IM node.exe 或 java.exe,按端口找 PID)或 docker compose down 对应容器。"
        }
    }
}

function Compose { docker compose -f $composeFile @args; if ($LASTEXITCODE -ne 0) { throw "docker compose $args 失败(exit $LASTEXITCODE)" } }

switch ($Action) {
    "build" {
        Write-Host "== [1/2] gradle :api:bootJar(宿主构建,容器内不跑 gradle)" -ForegroundColor Cyan
        Push-Location (Join-Path $repoRoot "backend")
        try { ./gradlew :api:bootJar -q; if ($LASTEXITCODE -ne 0) { throw "bootJar 失败" } }
        finally { Pop-Location }
        $jar = Get-ChildItem (Join-Path $repoRoot "backend\api\build\libs") -Filter "api-*-SNAPSHOT.jar" |
            Where-Object { $_.Name -notlike "*-plain.jar" } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $jar) { throw "找不到 bootJar 产物(backend/api/build/libs/api-*-SNAPSHOT.jar)" }
        Copy-Item $jar.FullName (Join-Path $repoRoot "backend\app.jar") -Force
        Write-Host "== [2/2] docker compose build ($Env)" -ForegroundColor Cyan
        Compose build
        Write-Host "`n镜像就绪:yangzhou-api:local / yangzhou-web:local" -ForegroundColor Green
    }
    "up" {
        Assert-PortsFree
        Compose up -d
        Write-Host "`n已启动 $Env :" -ForegroundColor Green
        if ($ports -contains 3000) { Write-Host "  web http://localhost:3000" }
        if ($ports -contains 3001) { Write-Host "  web http://localhost:3001" }
        Write-Host "  api http://localhost:$($ports[-1])"
    }
    "down" {
        Compose down   # 刻意不带 -v:本组 compose 无卷,数据全在外部 postgres
        Write-Host "`n已停止 $Env(外部 postgres/rustfs 不受影响)" -ForegroundColor Green
    }
    "status" {
        Compose ps
    }
}
