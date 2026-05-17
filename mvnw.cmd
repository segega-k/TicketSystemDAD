@echo off
where mvn >nul 2>nul
if %ERRORLEVEL% neq 0 echo Please use mvnw on macOS/Linux or install Maven for Windows.
mvn %*
