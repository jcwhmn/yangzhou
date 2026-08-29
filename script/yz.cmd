@echo off
setlocal enabledelayedexpansion
rem yz - yangzhou CLI wrapper (auto-builds fat-jar on first run)
cd /d "%~dp0.."

set "JAR="
for %%f in ("backend\cli\build\libs\yz-*.jar") do set "JAR=%%~ff"
if not defined JAR (
  echo First run: building CLI fat-jar...
  pushd backend
  call gradle :cli:jar --console=plain
  popd
  for %%f in ("backend\cli\build\libs\yz-*.jar") do set "JAR=%%~ff"
)

if not defined JAR (
  echo Build failed: yz jar not found
  exit /b 1
)
java -jar "%JAR%" %*
