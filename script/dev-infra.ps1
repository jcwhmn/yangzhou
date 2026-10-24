# YPJ-77:本机基础设施一键起停(仅项目独用服务;postgres 为外部共享实例,不归本脚本管)
# 用法: .\script\dev-infra.ps1 up|down|status
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("up", "down", "status")]
    [string]$Action
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path $PSScriptRoot "..\infra\compose\docker-compose.yaml"

Write-Host "== yangzhou 项目独用基础设施(compose: $composeFile)" -ForegroundColor Cyan
Write-Host "   注意:postgres 为外部共享实例(F:/code/docker-compose/postgresql, localhost:5432),不归本脚本管理" -ForegroundColor Yellow

switch ($Action) {
    "up" {
        docker compose -f $composeFile up -d
        if ($LASTEXITCODE -eq 0) {
            Write-Host "`n已启动:rustfs(S3 API localhost:9000 / 控制台 localhost:9001,凭据 rustfsadmin/rustfsadmin)" -ForegroundColor Green
        }
    }
    "down" {
        # 刻意不带 -v:卷(rustfs_data/rustfs_logs)保留,附件数据不丢
        docker compose -f $composeFile down
        if ($LASTEXITCODE -eq 0) { Write-Host "`n已停止(卷已保留,数据不丢)" -ForegroundColor Green }
    }
    "status" {
        docker compose -f $composeFile ps
    }
}
