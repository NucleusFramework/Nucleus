@echo off
REM Builds sample_tao_webview.dll (direct WebView2 SDK + DComp) for the
REM Windows sample-tao "WebView" tab demo. Replaces the earlier wry-based
REM Rust crate.
REM
REM First-run bootstrap:
REM   1. Downloads nuget.exe to this directory if missing.
REM   2. `nuget install Microsoft.Web.WebView2` to fetch headers + the
REM      WebView2Loader.dll for x64 and ARM64.
REM Both artifacts are gitignored — we don't vendor the SDK in the repo.
REM
REM Outputs:
REM   - sample_tao_webview.dll  -> resources/nucleus/native/win32-{x64,aarch64}/
REM   - WebView2Loader.dll      -> same dir (sidecar; loaded by name from
REM                                          our DLL via SetDllDirectoryW)

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "RESOURCE_DIR=%SCRIPT_DIR%..\..\resources\nucleus\native"
set "OUT_DIR_X64=%RESOURCE_DIR%\win32-x64"
set "OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64"
set "PACKAGES_DIR=%SCRIPT_DIR%packages"
set "WV2_PACKAGE=Microsoft.Web.WebView2"
set "WV2_VERSION=1.0.2210.55"
set "WV2_DIR=%PACKAGES_DIR%\%WV2_PACKAGE%.%WV2_VERSION%"

REM ---- JAVA_HOME check ----
if "%JAVA_HOME%"=="" (
    echo ERROR: JAVA_HOME is not set. >&2
    exit /b 1
)
if not exist "%JAVA_HOME%\include\jni.h" (
    echo ERROR: JNI headers not found at %JAVA_HOME%\include >&2
    exit /b 1
)

REM ---- nuget bootstrap ----
if not exist "%SCRIPT_DIR%nuget.exe" (
    echo Downloading nuget.exe...
    powershell -NoProfile -Command "Invoke-WebRequest -Uri https://dist.nuget.org/win-x86-commandline/latest/nuget.exe -OutFile '%SCRIPT_DIR%nuget.exe' -UseBasicParsing"
    if errorlevel 1 (
        echo ERROR: failed to download nuget.exe >&2
        exit /b 1
    )
)

REM ---- WebView2 SDK fetch ----
if not exist "%WV2_DIR%\build\native\include\WebView2.h" (
    echo Fetching %WV2_PACKAGE% %WV2_VERSION%...
    "%SCRIPT_DIR%nuget.exe" install %WV2_PACKAGE% -Version %WV2_VERSION% -OutputDirectory "%PACKAGES_DIR%" -Source https://api.nuget.org/v3/index.json -NonInteractive
    if errorlevel 1 (
        echo ERROR: nuget install failed >&2
        exit /b 1
    )
)

set "WV2_INCLUDE=%WV2_DIR%\build\native\include"
set "WV2_LOADER_X64=%WV2_DIR%\build\native\x64\WebView2Loader.dll"
set "WV2_LOADER_ARM64=%WV2_DIR%\build\native\arm64\WebView2Loader.dll"

if not exist "%WV2_INCLUDE%\WebView2.h" (
    echo ERROR: WebView2.h not found after nuget install >&2
    exit /b 1
)

REM ---- Locate vcvarsall.bat ----
set "VCVARSALL="
for %%v in (2022 2019 2017) do (
    for %%e in (Enterprise Professional Community BuildTools) do (
        if exist "C:\Program Files\Microsoft Visual Studio\%%v\%%e\VC\Auxiliary\Build\vcvarsall.bat" (
            set "VCVARSALL=C:\Program Files\Microsoft Visual Studio\%%v\%%e\VC\Auxiliary\Build\vcvarsall.bat"
            goto :found_vc
        )
        if exist "C:\Program Files (x86)\Microsoft Visual Studio\%%v\%%e\VC\Auxiliary\Build\vcvarsall.bat" (
            set "VCVARSALL=C:\Program Files (x86)\Microsoft Visual Studio\%%v\%%e\VC\Auxiliary\Build\vcvarsall.bat"
            goto :found_vc
        )
    )
)
:found_vc
if "%VCVARSALL%"=="" (
    echo ERROR: Could not locate vcvarsall.bat. >&2
    exit /b 1
)
echo Using vcvarsall.bat: %VCVARSALL%

if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

REM ===========================================================================
REM x64
REM ===========================================================================

setlocal
call "%VCVARSALL%" x64 >nul
if errorlevel 1 (
    echo ERROR: vcvarsall x64 failed >&2
    exit /b 1
)

echo.
echo === Building sample_tao_webview.dll (x64) ===
pushd "%SCRIPT_DIR%"
cl /LD /O2 /EHsc /std:c++17 /nologo /MT ^
    /D_UNICODE /DUNICODE ^
    /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" ^
    /I"%WV2_INCLUDE%" ^
    sample_webview.cpp ^
    /Fe:"%OUT_DIR_X64%\sample_tao_webview.dll" ^
    /link kernel32.lib user32.lib ole32.lib oleaut32.lib dcomp.lib
set "CL_RC=%ERRORLEVEL%"
popd
if not "%CL_RC%"=="0" (
    echo ERROR: x64 compilation failed >&2
    exit /b 1
)
copy /Y "%WV2_LOADER_X64%" "%OUT_DIR_X64%\WebView2Loader.dll" >nul
del /q "%OUT_DIR_X64%\*.obj" "%OUT_DIR_X64%\*.lib" "%OUT_DIR_X64%\*.exp" 2>nul
del /q "%SCRIPT_DIR%*.obj" "%SCRIPT_DIR%*.lib" "%SCRIPT_DIR%*.exp" 2>nul
endlocal

REM ===========================================================================
REM ARM64
REM ===========================================================================

setlocal
call "%VCVARSALL%" x64_arm64 >nul
if errorlevel 1 (
    echo WARNING: vcvarsall x64_arm64 failed - skipping ARM64 >&2
    endlocal
    goto :clear_cache
)

echo.
echo === Building sample_tao_webview.dll (ARM64) ===
pushd "%SCRIPT_DIR%"
cl /LD /O2 /EHsc /std:c++17 /nologo /MT ^
    /D_UNICODE /DUNICODE ^
    /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" ^
    /I"%WV2_INCLUDE%" ^
    sample_webview.cpp ^
    /Fe:"%OUT_DIR_ARM64%\sample_tao_webview.dll" ^
    /link kernel32.lib user32.lib ole32.lib oleaut32.lib dcomp.lib
set "CL_RC=%ERRORLEVEL%"
popd
if not "%CL_RC%"=="0" (
    echo WARNING: ARM64 compilation failed >&2
    endlocal
    goto :clear_cache
)
copy /Y "%WV2_LOADER_ARM64%" "%OUT_DIR_ARM64%\WebView2Loader.dll" >nul
del /q "%OUT_DIR_ARM64%\*.obj" "%OUT_DIR_ARM64%\*.lib" "%OUT_DIR_ARM64%\*.exp" 2>nul
del /q "%SCRIPT_DIR%*.obj" "%SCRIPT_DIR%*.lib" "%SCRIPT_DIR%*.exp" 2>nul
endlocal

REM ===========================================================================
:clear_cache
if exist "%LOCALAPPDATA%\nucleus\native" (
    rmdir /s /q "%LOCALAPPDATA%\nucleus\native"
    echo Cleared NativeLibraryLoader cache: %LOCALAPPDATA%\nucleus\native
)

echo.
echo Built artifacts:
if exist "%OUT_DIR_X64%\sample_tao_webview.dll"   echo   %OUT_DIR_X64%\sample_tao_webview.dll
if exist "%OUT_DIR_X64%\WebView2Loader.dll"        echo   %OUT_DIR_X64%\WebView2Loader.dll
if exist "%OUT_DIR_ARM64%\sample_tao_webview.dll" echo   %OUT_DIR_ARM64%\sample_tao_webview.dll
if exist "%OUT_DIR_ARM64%\WebView2Loader.dll"      echo   %OUT_DIR_ARM64%\WebView2Loader.dll

endlocal
