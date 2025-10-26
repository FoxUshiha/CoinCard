@echo off
setlocal ENABLEDELAYEDEXPANSION
pushd "%~dp0"

REM ====== CONFIG ======
set SPIGOT=spigot-api-1.21.6-R0.1-SNAPSHOT.jar
set VAULT=vault.jar

REM ====== PREP ======
if not exist out mkdir out
if not exist out\classes mkdir out\classes
if exist out\CoinCard.jar del /q out\CoinCard.jar

echo === Compilando CoinCard ===
javac -encoding UTF-8 -Xlint:deprecation -Xlint:unchecked ^
 -classpath ".;%SPIGOT%;%VAULT%" ^
 -d out\classes ^
 src\com\foxsrv\coincard\*.java ^
 src\com\foxsrv\coincard\core\*.java ^
 src\com\foxsrv\coincard\io\*.java

if errorlevel 1 (
  echo [ERRO] Falha na compilacao.
  pause
  popd
  exit /b 1
)

echo === Copiando recursos para DENTRO do JAR ===
copy /Y plugin.yml out\classes >nul
copy /Y config.yml out\classes >nul
copy /Y users.yml  out\classes >nul

echo === (Opcional) Semear pasta de dados do servidor ===
if not exist "plugins\CoinCard" mkdir "plugins\CoinCard"
copy /Y config.yml "plugins\CoinCard\" >nul
copy /Y users.yml  "plugins\CoinCard\" >nul

echo === Empacotando JAR ===
pushd out\classes
jar cvf ..\CoinCard.jar *
popd

echo.
echo === Build concluido: out\CoinCard.jar ===
pause
popd
