@echo off
title Compilador CoinCard - Java 17

echo ============================================
echo Compilador do Plugin CoinCard
echo ============================================
echo.

echo Procurando Java 17 instalado...
echo.

set JDK_PATH=

rem Procura JDK 17 em locais comuns
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Microsoft\jdk-17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ============================================
    echo ERRO: JDK 17 nao encontrado!
    echo Instale o Java 17 JDK e tente novamente.
    echo ============================================
    pause
    exit /b 1
)

echo Java 17 encontrado em: %JDK_PATH%
echo.

set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

echo ============================================
echo Preparando ambiente de compilacao...
echo ============================================
echo.

echo Limpando pasta out...
if exist out (
    rmdir /s /q out >nul 2>&1
)
mkdir out
mkdir out\com
mkdir out\com\foxsrv
mkdir out\com\foxsrv\coincard

echo.
echo ============================================
echo Verificando dependencias...
echo ============================================
echo.

REM Verificar Spigot API
if not exist spigot-api-1.20.1-R0.1-SNAPSHOT.jar (
    echo [ERRO] spigot-api-1.20.1-R0.1-SNAPSHOT.jar nao encontrado!
    echo.
    echo Certifique-se de que o arquivo está na pasta raiz.
    echo Esperado: spigot-api-1.20.1-R0.1-SNAPSHOT.jar
    pause
    exit /b 1
) else (
    echo [OK] Spigot API encontrado
)

REM Verificar Vault
if not exist Vault.jar (
    echo [ERRO] Vault.jar nao encontrado!
    echo.
    echo Certifique-se de que o arquivo Vault.jar está na pasta raiz.
    pause
    exit /b 1
) else (
    echo [OK] Vault encontrado
)

REM Verificar PlaceholderAPI (soft-dependencia, nao obrigatoria para compilar)
if exist PlaceholderAPI.jar (
    echo [OK] PlaceholderAPI encontrado
    set PLACEHOLDER_API=PlaceholderAPI.jar
) else (
    echo [AVISO] PlaceholderAPI.jar nao encontrado - Placeholders nao estarao disponiveis
    echo Para suporte a placeholders, coloque PlaceholderAPI.jar na pasta raiz.
    set PLACEHOLDER_API=
)

echo.
echo ============================================
echo Compilando CoinCard...
echo ============================================
echo.

REM Construir classpath com todas as dependencias
set CLASSPATH=spigot-api-1.20.1-R0.1-SNAPSHOT.jar;Vault.jar
if defined PLACEHOLDER_API (
    set CLASSPATH=%CLASSPATH%;%PLACEHOLDER_API%
)

REM Compilar o plugin
%JAVAC% --release 17 -d out ^
-classpath "%CLASSPATH%" ^
-sourcepath src ^
src/com/foxsrv/coincard/CoinCardPlugin.java

if %errorlevel% neq 0 (
    echo ============================================
    echo ERRO AO COMPILAR O PLUGIN!
    echo ============================================
    echo.
    echo Verifique os erros acima e corrija o codigo.
    pause
    exit /b 1
)

echo.
echo Compilacao concluida com sucesso!
echo.

echo ============================================
echo Copiando arquivos de recursos...
echo ============================================
echo.

REM Copiar plugin.yml
if exist plugin.yml (
    copy plugin.yml out\ >nul
    echo [OK] plugin.yml copiado
) else if exist resources\plugin.yml (
    copy resources\plugin.yml out\ >nul
    echo [OK] plugin.yml copiado de resources\
) else (
    echo [AVISO] plugin.yml nao encontrado!
    echo Criando plugin.yml padrao...
    
    (
        echo name: CoinCard
        echo version: 1.0
        echo main: com.foxsrv.coincard.CoinCardPlugin
        echo api-version: "1.20"
        echo depend: [Vault]
        echo softdepend: [PlaceholderAPI]
        echo author: FoxSRV
        echo description: Sistema de cartões para transações de coins
        echo.
        echo commands:
        echo   coin:
        echo     description: Comando principal do CoinCard
        echo     usage: /coin ^<subcomando^>
        echo.
        echo permissions:
        echo   coin.admin:
        echo     description: Permissão para comandos administrativos
        echo     default: op
    ) > out\plugin.yml
    echo [OK] plugin.yml criado automaticamente
)

REM Copiar config.yml da raiz ou resources
if exist config.yml (
    copy config.yml out\ >nul
    echo [OK] config.yml copiado da raiz
) else if exist resources\config.yml (
    copy resources\config.yml out\ >nul
    echo [OK] config.yml copiado de resources\
) else (
    echo [AVISO] config.yml nao encontrado!
    echo Criando config.yml padrao...
    
    (
        echo # CoinCard Configuration
        echo # Server Vault Account UUID
        echo Server: "5e8127e5-646b-36d6-9ff7-ace1050597d8"
        echo.
        echo # Server Card ID
        echo Card: "e1301fadfc35"
        echo.
        echo # Vault recebido por 1.00000000 coin (coins->vault^)
        echo Buy: 7200.0000
        echo.
        echo # Coins recebidos por 1.0000 vault (vault->coins^)
        echo Sell: 0.00010500
        echo.
        echo # API URL
        echo API: "http://coin.foxsrv.net:26450/"
        echo.
        echo # Geral
        echo QueueIntervalTicks: 20
        echo PerUserCooldownMs: 1000
        echo TimeoutMs: 10000
    ) > out\config.yml
    echo [OK] config.yml criado automaticamente
)

REM Copiar users.yml da raiz ou resources
if exist users.yml (
    copy users.yml out\ >nul
    echo [OK] users.yml copiado da raiz
) else if exist resources\users.yml (
    copy resources\users.yml out\ >nul
    echo [OK] users.yml copiado de resources\
) else (
    echo [AVISO] users.yml nao encontrado!
    echo Criando users.yml padrao...
    
    (
        echo # CoinCard Users Database
        echo Users:
        echo   "5e8127e5-646b-36d6-9ff7-ace1050597d8":
        echo     nick: "Server"
        echo     Card: "e1301fadfc35"
    ) > out\users.yml
    echo [OK] users.yml criado automaticamente
)

echo.
echo ============================================
echo Criando arquivo JAR...
echo ============================================
echo.

cd out

REM Criar JAR com todos os recursos
%JAR% cf CoinCard.jar com plugin.yml config.yml users.yml

cd ..

echo.
echo ============================================
echo PLUGIN COMPILADO COM SUCESSO!
echo ============================================
echo.
echo Arquivo gerado: out\CoinCard.jar
echo.
echo Tamanho do arquivo:
dir out\CoinCard.jar
echo.
echo ============================================
echo Para instalar:
echo 1 - Copie out\CoinCard.jar para a pasta plugins do servidor
echo 2 - Certifique-se de ter Vault.jar e PlaceholderAPI.jar (opcional) na pasta plugins
echo 3 - Reinicie o servidor ou use /reload confirm
echo ============================================
echo.

echo Deseja copiar o JAR para a pasta plugins do servidor? (S/N)
set /p COPY_PLUGINS=

if /i "%COPY_PLUGINS%"=="S" (
    echo.
    echo Digite o caminho da pasta plugins do servidor:
    echo (exemplo: C:\Servidor\plugins)
    set /p SERVER_PLUGINS=
    
    if exist "%SERVER_PLUGINS%" (
        copy out\CoinCard.jar "%SERVER_PLUGINS%" >nul
        echo [OK] CoinCard.jar copiado para %SERVER_PLUGINS%
    ) else (
        echo [ERRO] Caminho nao encontrado: %SERVER_PLUGINS%
    )
)

echo.
echo ============================================
echo Processo finalizado!
echo ============================================
echo.

pause