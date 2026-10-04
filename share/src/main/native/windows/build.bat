@echo off
REM Builds the Windows DLL into src/main/resources/nucleus/native/win32-x64 and,
REM when ARM64 cross tools are available, also into win32-aarch64.
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "NATIVE_DIR=%SCRIPT_DIR%.."
set "RESOURCE_DIR=%NATIVE_DIR%\..\resources\nucleus\native"
set "OUT_DIR_X64=%RESOURCE_DIR%\win32-x64"
set "OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64"

if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

set "RUSTUP_BIN="
if exist "%USERPROFILE%\.cargo\bin\rustup.exe" set "RUSTUP_BIN=%USERPROFILE%\.cargo\bin\rustup.exe"
if "%RUSTUP_BIN%"=="" if exist "%ProgramFiles%\Rustup\bin\rustup.exe" set "RUSTUP_BIN=%ProgramFiles%\Rustup\bin\rustup.exe"
if "%RUSTUP_BIN%"=="" (
    where rustup >nul 2>&1
    if not errorlevel 1 for /f "usebackq tokens=*" %%i in (`where rustup`) do (
        set "RUSTUP_BIN=%%i"
        goto :rustup_found
    )
)
:rustup_found
if "%RUSTUP_BIN%"=="" (
    echo ERROR: rustup not found. Install Rust from https://rustup.rs/ >&2
    exit /b 1
)

set "CARGO_BIN="
if exist "%USERPROFILE%\.cargo\bin\cargo.exe" set "CARGO_BIN=%USERPROFILE%\.cargo\bin\cargo.exe"
if "%CARGO_BIN%"=="" (
    for /f "usebackq tokens=*" %%i in (`"%RUSTUP_BIN%" which cargo`) do set "CARGO_BIN=%%i"
)
if "%CARGO_BIN%"=="" (
    echo ERROR: cargo not found. Install Rust from https://rustup.rs/ >&2
    exit /b 1
)

set "VCVARSALL="
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -prerelease -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
        if exist "%%i\VC\Auxiliary\Build\vcvarsall.bat" set "VCVARSALL=%%i\VC\Auxiliary\Build\vcvarsall.bat"
    )
)
if "%VCVARSALL%"=="" (
    echo ERROR: Could not locate vcvarsall.bat. Install Visual Studio Build Tools. >&2
    exit /b 1
)

echo.
echo === Building nucleus_share.dll (x64) ===
setlocal
call "%VCVARSALL%" x64
if errorlevel 1 (
    echo ERROR: vcvarsall x64 failed >&2
    exit /b 1
)
pushd "%NATIVE_DIR%"
"%RUSTUP_BIN%" target add x86_64-pc-windows-msvc >nul 2>&1
"%CARGO_BIN%" build --release --target x86_64-pc-windows-msvc
if errorlevel 1 (
    echo ERROR: cargo build x64 failed >&2
    popd
    exit /b 1
)
copy /Y "target\x86_64-pc-windows-msvc\release\nucleus_share.dll" "%OUT_DIR_X64%\nucleus_share.dll" >nul
popd
endlocal

echo.
echo === Building nucleus_share.dll (ARM64, optional) ===
if exist "%OUT_DIR_ARM64%\nucleus_share.dll" del /q "%OUT_DIR_ARM64%\nucleus_share.dll" 2>nul
setlocal
call "%VCVARSALL%" x64_arm64
if errorlevel 1 (
    echo WARNING: vcvarsall x64_arm64 failed. Skipping ARM64 build. >&2
    endlocal
    goto :clear_cache
)
pushd "%NATIVE_DIR%"
"%RUSTUP_BIN%" target add aarch64-pc-windows-msvc >nul 2>&1
"%CARGO_BIN%" build --release --target aarch64-pc-windows-msvc
if errorlevel 1 (
    echo WARNING: cargo build ARM64 failed. Skipping ARM64 artifact. >&2
    popd
    endlocal
    goto :clear_cache
)
copy /Y "target\aarch64-pc-windows-msvc\release\nucleus_share.dll" "%OUT_DIR_ARM64%\nucleus_share.dll" >nul
popd
endlocal

:clear_cache
if exist "%USERPROFILE%\.cache\nucleus\native" (
    rmdir /s /q "%USERPROFILE%\.cache\nucleus\native"
    echo Cleared NativeLibraryLoader cache: %USERPROFILE%\.cache\nucleus\native
)
if exist "%LOCALAPPDATA%\nucleus\native" (
    rmdir /s /q "%LOCALAPPDATA%\nucleus\native"
    echo Cleared NativeLibraryLoader cache: %LOCALAPPDATA%\nucleus\native
)

echo.
echo Built Windows DLLs:
if exist "%OUT_DIR_X64%\nucleus_share.dll" echo   %OUT_DIR_X64%\nucleus_share.dll
if exist "%OUT_DIR_ARM64%\nucleus_share.dll" echo   %OUT_DIR_ARM64%\nucleus_share.dll

exit /b 0
