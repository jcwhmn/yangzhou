@echo off
rem yz - yangzhou CLI wrapper (release layout: jar beside script; dev layout: repo build; else auto-build)
rem chcp 65001: force UTF-8 console so Chinese output is not garbled under GBK codepage
chcp 65001 >nul
setlocal enabledelayedexpansion

rem 1) jar beside this script (release layout from GitHub Releases)
set "JAR="
for %%f in ("%~dp0yz-*.jar") do set "JAR=%%~ff"
if defined JAR goto run

rem 2) repo layout (dev)
cd /d "%~dp0.."
for %%f in ("backend\cli\build\libs\yz-*.jar") do set "JAR=%%~ff"
if defined JAR goto run

rem 3) auto-build (dev, first run)
echo First run: building CLI fat-jar...
pushd backend
call gradle :cli:jar --console=plain
popd
for %%f in ("backend\cli\build\libs\yz-*.jar") do set "JAR=%%~ff"

:run
if not defined JAR (
  echo Build failed: yz jar not found
  exit /b 1
)
java -jar "%JAR%" %*
