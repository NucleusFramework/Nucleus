@echo off
REM Builds sample_tao_webview.dll (wry-backed WebView2 wrapper) for the
REM Windows sample-tao "WebView" tab demo. WebView2 Runtime must be
REM installed on the target machine (bundled with Edge on modern Windows).
REM
REM Outputs in src/main/resources/nucleus/native/{win32-x64,win32-aarch64}/.

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "RESOURCE_DIR=%SCRIPT_DIR%..\..\resources\nucleus\native"
set "OUT_DIR_X64=%RESOURCE_DIR%\win32-x64"
set "OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64"

where cargo >nul 2>&1
if errorlevel 1 (
    echo ERROR: cargo not found in PATH. Install rustup from https://rustup.rs/ >&2
    exit /b 1
)

if not exist "%OUT_DIR_X64%"   mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

echo.
echo === Building sample_tao_webview (wry, x64) ===
pushd "%SCRIPT_DIR%"
rustup target add x86_64-pc-windows-msvc >nul 2>&1
cargo build --release --target x86_64-pc-windows-msvc
if errorlevel 1 (
    echo ERROR: cargo build x64 failed >&2
    popd
    exit /b 1
)
copy /Y "target\x86_64-pc-windows-msvc\release\sample_tao_webview.dll" "%OUT_DIR_X64%\sample_tao_webview.dll" >nul

echo.
echo === Building sample_tao_webview (wry, ARM64) ===
rustup target add aarch64-pc-windows-msvc >nul 2>&1
cargo build --release --target aarch64-pc-windows-msvc
if errorlevel 1 (
    echo WARNING: cargo build ARM64 failed - skipping ARM64. >&2
) else (
    copy /Y "target\aarch64-pc-windows-msvc\release\sample_tao_webview.dll" "%OUT_DIR_ARM64%\sample_tao_webview.dll" >nul
)
popd

REM Clear NativeLibraryLoader cache so the freshly built DLL is picked up.
if exist "%USERPROFILE%\.cache\nucleus\native" (
    rmdir /s /q "%USERPROFILE%\.cache\nucleus\native"
)
if exist "%LOCALAPPDATA%\nucleus\native" (
    rmdir /s /q "%LOCALAPPDATA%\nucleus\native"
)

echo.
echo Built DLLs:
if exist "%OUT_DIR_X64%\sample_tao_webview.dll"   echo   %OUT_DIR_X64%\sample_tao_webview.dll
if exist "%OUT_DIR_ARM64%\sample_tao_webview.dll" echo   %OUT_DIR_ARM64%\sample_tao_webview.dll

endlocal
