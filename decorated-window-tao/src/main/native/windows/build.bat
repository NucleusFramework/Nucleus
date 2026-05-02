@echo off
REM Builds three artifacts for the Tao Windows backend:
REM   - nucleus_tao.dll               (Rust crate, Tao + JNI)
REM   - nucleus_tao_windows_deco.dll  (C, custom WndProc + DwmExtendFrameIntoClientArea)
REM   - nucleus_tao_gl.dll            (C, WGL helper for Skia GL backend)
REM
REM Outputs in src/main/resources/nucleus/native/{win32-x64,win32-aarch64}/.
REM
REM Prerequisites:
REM   - rustup with x86_64-pc-windows-msvc (and aarch64-pc-windows-msvc) targets
REM   - Visual Studio 2019/2022 Build Tools with x64 + ARM64 support
REM   - JAVA_HOME pointing to a JDK with `include/jni.h` and `include/win32/`
REM
REM Usage: build.bat

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "NATIVE_DIR=%SCRIPT_DIR%.."
set "DECO_SRC=%SCRIPT_DIR%nucleus_tao_windows_deco.c"
set "GL_SRC=%SCRIPT_DIR%nucleus_tao_gl.c"
set "A11Y_SRC=%SCRIPT_DIR%nucleus_tao_a11y.c"
set "RESOURCE_DIR=%NATIVE_DIR%\..\resources\nucleus\native"
set "OUT_DIR_X64=%RESOURCE_DIR%\win32-x64"
set "OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64"

REM ---- Check JAVA_HOME ----
if "%JAVA_HOME%"=="" (
    echo ERROR: JAVA_HOME is not set. >&2
    exit /b 1
)
if not exist "%JAVA_HOME%\include\jni.h" (
    echo ERROR: JNI headers not found at %JAVA_HOME%\include >&2
    exit /b 1
)

set "JNI_INCLUDE=%JAVA_HOME%\include"
set "JNI_INCLUDE_WIN32=%JAVA_HOME%\include\win32"

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

REM ---- Check cargo ----
where cargo >nul 2>&1
if errorlevel 1 (
    echo ERROR: cargo not found in PATH. Install rustup from https://rustup.rs/ >&2
    exit /b 1
)

if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

REM ===========================================================================
REM 1) Rust crate (nucleus_tao.dll)
REM ===========================================================================

echo.
echo === Building nucleus_tao.dll (x64) ===
pushd "%NATIVE_DIR%"
rustup target add x86_64-pc-windows-msvc >nul 2>&1
cargo build --release --target x86_64-pc-windows-msvc
if errorlevel 1 (
    echo ERROR: cargo build x64 failed >&2
    popd
    exit /b 1
)
copy /Y "target\x86_64-pc-windows-msvc\release\nucleus_tao.dll" "%OUT_DIR_X64%\nucleus_tao.dll" >nul
popd

echo.
echo === Building nucleus_tao.dll (ARM64) ===
pushd "%NATIVE_DIR%"
rustup target add aarch64-pc-windows-msvc >nul 2>&1
cargo build --release --target aarch64-pc-windows-msvc
if errorlevel 1 (
    echo WARNING: cargo build ARM64 failed - skipping ARM64. >&2
    set "SKIP_ARM64=1"
) else (
    copy /Y "target\aarch64-pc-windows-msvc\release\nucleus_tao.dll" "%OUT_DIR_ARM64%\nucleus_tao.dll" >nul
)
popd

REM ===========================================================================
REM 2 + 3) C helpers — compile both DLLs in the same vcvars environment
REM ===========================================================================

echo.
echo === Building C helpers (x64) ===
setlocal
call "%VCVARSALL%" x64
if errorlevel 1 (
    echo ERROR: vcvarsall x64 failed >&2
    exit /b 1
)

cl /LD /O1 /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%DECO_SRC%" ^
    /Fe:"%OUT_DIR_X64%\nucleus_tao_windows_deco.dll" ^
    /link /NODEFAULTLIB /ENTRY:DllMain kernel32.lib user32.lib dwmapi.lib gdi32.lib shell32.lib
if errorlevel 1 (
    echo ERROR: x64 deco compilation failed >&2
    exit /b 1
)

cl /LD /O1 /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%GL_SRC%" ^
    /Fe:"%OUT_DIR_X64%\nucleus_tao_gl.dll" ^
    /link /NODEFAULTLIB /ENTRY:DllMain opengl32.lib gdi32.lib user32.lib kernel32.lib
if errorlevel 1 (
    echo ERROR: x64 GL compilation failed >&2
    exit /b 1
)

REM nucleus_tao_a11y.dll — UI Automation provider. Needs the C runtime
REM (HeapAlloc-friendly libcmt) because uiautomationcore.lib pulls in C++
REM statics that require __security_check_cookie / floating-point support.
cl /LD /O1 /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%A11Y_SRC%" ^
    /Fe:"%OUT_DIR_X64%\nucleus_tao_a11y.dll" ^
    /link /NODEFAULTLIB /ENTRY:DllMain ^
    kernel32.lib user32.lib comctl32.lib ole32.lib oleaut32.lib uuid.lib uiautomationcore.lib
if errorlevel 1 (
    echo ERROR: x64 a11y compilation failed >&2
    exit /b 1
)
endlocal

del /q "%OUT_DIR_X64%\*.obj" "%OUT_DIR_X64%\*.lib" "%OUT_DIR_X64%\*.exp" 2>nul

if defined SKIP_ARM64 goto :clear_cache

echo.
echo === Building C helpers (ARM64) ===
setlocal
call "%VCVARSALL%" x64_arm64
if errorlevel 1 (
    echo WARNING: vcvarsall x64_arm64 failed - skipping ARM64 C helpers >&2
    endlocal
    goto :clear_cache
)

cl /LD /O1 /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%DECO_SRC%" ^
    /Fe:"%OUT_DIR_ARM64%\nucleus_tao_windows_deco.dll" ^
    /link /NODEFAULTLIB /ENTRY:DllMain kernel32.lib user32.lib dwmapi.lib gdi32.lib shell32.lib
if errorlevel 1 (
    echo WARNING: ARM64 deco compilation failed >&2
    endlocal
    goto :clear_cache
)

cl /LD /O1 /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%GL_SRC%" ^
    /Fe:"%OUT_DIR_ARM64%\nucleus_tao_gl.dll" ^
    /link /NODEFAULTLIB /ENTRY:DllMain opengl32.lib gdi32.lib user32.lib kernel32.lib
if errorlevel 1 (
    echo WARNING: ARM64 GL compilation failed >&2
    endlocal
    goto :clear_cache
)

cl /LD /O1 /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%A11Y_SRC%" ^
    /Fe:"%OUT_DIR_ARM64%\nucleus_tao_a11y.dll" ^
    /link /NODEFAULTLIB /ENTRY:DllMain ^
    kernel32.lib user32.lib comctl32.lib ole32.lib oleaut32.lib uuid.lib uiautomationcore.lib
if errorlevel 1 (
    echo WARNING: ARM64 a11y compilation failed >&2
    endlocal
    goto :clear_cache
)
endlocal

del /q "%OUT_DIR_ARM64%\*.obj" "%OUT_DIR_ARM64%\*.lib" "%OUT_DIR_ARM64%\*.exp" 2>nul

REM ===========================================================================
REM Clear NativeLibraryLoader cache so fresh DLLs are picked up
REM ===========================================================================
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
echo Built DLLs:
if exist "%OUT_DIR_X64%\nucleus_tao.dll"               echo   %OUT_DIR_X64%\nucleus_tao.dll
if exist "%OUT_DIR_X64%\nucleus_tao_windows_deco.dll"  echo   %OUT_DIR_X64%\nucleus_tao_windows_deco.dll
if exist "%OUT_DIR_X64%\nucleus_tao_gl.dll"            echo   %OUT_DIR_X64%\nucleus_tao_gl.dll
if exist "%OUT_DIR_ARM64%\nucleus_tao.dll"               echo   %OUT_DIR_ARM64%\nucleus_tao.dll
if exist "%OUT_DIR_ARM64%\nucleus_tao_windows_deco.dll"  echo   %OUT_DIR_ARM64%\nucleus_tao_windows_deco.dll
if exist "%OUT_DIR_ARM64%\nucleus_tao_gl.dll"            echo   %OUT_DIR_ARM64%\nucleus_tao_gl.dll

endlocal
