@echo off
setlocal enabledelayedexpansion
title Compilador CoinCard v1.0 - Java 17 (classpath completo)
echo ============================================
echo Compilador do Plugin CoinCard
echo (Sistema de Cartões e Transações) - Classpath corrigido
echo ============================================
echo.

:: Localizar JDK 17
set JDK_PATH=
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Microsoft\jdk-17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ERRO: JDK 17 nao encontrado!
    pause
    exit /b 1
)
echo Java 17 encontrado em: %JDK_PATH%
set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

:: Limpar e criar pasta out
if exist out rmdir /s /q out >nul 2>&1
mkdir out

echo ============================================
echo Montando CLASSPATH com todos os JARs...
echo ============================================
set CP=

:: JARs da raiz (Paper API, Vault, PlaceholderAPI)
if exist paper-api-1.20.1-R0.1-20230921.165944-178.jar (
    set CP=paper-api-1.20.1-R0.1-20230921.165944-178.jar
    echo [OK] paper-api-1.20.1
) else (
    echo [ERRO] paper-api nao encontrado!
    pause
    exit /b 1
)

if exist Vault.jar (
    set CP=!CP!;Vault.jar
    echo [OK] Vault.jar
) else (
    echo [AVISO] Vault.jar ausente
)

if exist PlaceholderAPI.jar (
    set CP=!CP!;PlaceholderAPI.jar
    echo [OK] PlaceholderAPI.jar
) else (
    echo [AVISO] PlaceholderAPI.jar ausente (placeholders nao serao compilados)
)

:: JARs da pasta bin
if exist bin\*.jar (
    echo [INFO] Adicionando JARs da pasta bin:
    for %%f in (bin\*.jar) do (
        set CP=!CP!;%%f
        echo   - %%~nxf
    )
) else (
    echo [AVISO] Pasta bin vazia ou inexistente
)

echo.
echo Classpath final (resumido):
echo !CP!
echo.

:: Compilar todos os .java (pacote com.foxsrv.coincard)
dir /s /b src\*.java > sources.txt 2>nul
if errorlevel 1 (
    echo ERRO: Nenhum arquivo .java encontrado em src
    pause
    exit /b 1
)

echo Compilando...
%JAVAC% --release 17 -d out -classpath "!CP!" -sourcepath src @sources.txt
if %errorlevel% neq 0 (
    echo ERRO na compilacao!
    del sources.txt >nul 2>&1
    pause
    exit /b 1
)
del sources.txt >nul 2>&1
echo Compilacao OK.

:: Copiar resources (plugin.yml, config.yml, users.yml se existir)
if exist resources\plugin.yml (
    copy resources\plugin.yml out\ >nul
    echo [OK] plugin.yml copiado
) else (
    echo [AVISO] plugin.yml nao encontrado
)

if exist resources\config.yml (
    copy resources\config.yml out\ >nul
    echo [OK] config.yml copiado
) else (
    echo [AVISO] config.yml nao encontrado
)

if exist resources\users.yml (
    copy resources\users.yml out\ >nul
    echo [OK] users.yml copiado
) else (
    echo [AVISO] users.yml nao encontrado (opcional)
)

:: Criar JAR
cd out
echo Criando CoinCard.jar...
%JAR% cf CoinCard.jar com plugin.yml config.yml
if exist users.yml %JAR% uf CoinCard.jar users.yml
cd ..

echo.
echo ============================================
echo SUCESSO! JAR gerado: out\CoinCard.jar
echo ============================================
dir out\CoinCard.jar

echo.
echo ============================================
echo RESUMO DA COMPILACAO:
echo ============================================
echo.
echo - Data/Hora: %date% %time%
echo - Java Version: 17
echo - Pacote: com.foxsrv.coincard
echo - Main Class: com.foxsrv.coincard.CoinCardPlugin
echo - Dependencias: Paper API, Vault, PlaceholderAPI, todos os JARs da pasta bin
echo.
echo ============================================
echo Para instalar:
echo ============================================
echo.
echo 1 - Copie out\CoinCard.jar para a pasta plugins do servidor Folia
echo 2 - Reinicie o servidor
echo 3 - Configure o arquivo config.yml se necessario
echo.
pause
