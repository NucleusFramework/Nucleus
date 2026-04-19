@echo off
REM Compiles nucleus_clipboard_windows.cpp into per-architecture DLLs (x64 + ARM64).
REM
REM Prerequisites: Visual Studio Build Tools (MSVC) with Windows SDK, JAVA_HOME set.
REM Usage: build.bat

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "SRC=%SCRIPT_DIR%nucleus_clipboard_windows.cpp"
set "RESOURCE_DIR=%SCRIPT_DIR%..\..\resources\nucleus\native"
set "OUT_DIR_X64=%RESOURCE_DIR%\win32-x64"
set "OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64"

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
    echo ERROR: Could not locate vcvarsall.bat. Install Visual Studio Build Tools. >&2
    exit /b 1
)

echo Using vcvarsall.bat: %VCVARSALL%

if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

REM ---- Compile x64 ----
echo.
echo === Building x64 DLL ===
setlocal
call "%VCVARSALL%" x64
if errorlevel 1 (
    echo ERROR: vcvarsall x64 failed >&2
    exit /b 1
)

cl /LD /O1 /EHsc /std:c++17 /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%SRC%" ^
    /Fe:"%OUT_DIR_X64%\nucleus_clipboard_windows.dll" ^
    /link user32.lib kernel32.lib shell32.lib gdi32.lib gdiplus.lib ole32.lib
if errorlevel 1 (
    echo ERROR: x64 compilation failed >&2
    exit /b 1
)
endlocal

del /q "%OUT_DIR_X64%\*.obj" "%OUT_DIR_X64%\*.lib" "%OUT_DIR_X64%\*.exp" 2>nul
del /q "%SCRIPT_DIR%\*.obj" 2>nul

REM ---- Compile ARM64 ----
echo.
echo === Building ARM64 DLL ===
setlocal
call "%VCVARSALL%" x64_arm64
if errorlevel 1 (
    echo WARNING: vcvarsall x64_arm64 failed. ARM64 cross-compilation may not be available. >&2
    endlocal
    goto :done
)

cl /LD /O1 /EHsc /std:c++17 /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%SRC%" ^
    /Fe:"%OUT_DIR_ARM64%\nucleus_clipboard_windows.dll" ^
    /link user32.lib kernel32.lib shell32.lib gdi32.lib gdiplus.lib ole32.lib
if errorlevel 1 (
    echo WARNING: ARM64 compilation failed. >&2
    endlocal
    goto :done
)
endlocal

del /q "%OUT_DIR_ARM64%\*.obj" "%OUT_DIR_ARM64%\*.lib" "%OUT_DIR_ARM64%\*.exp" 2>nul
del /q "%SCRIPT_DIR%\*.obj" 2>nul

:done
echo.
echo Built DLLs:
if exist "%OUT_DIR_X64%\nucleus_clipboard_windows.dll" echo   %OUT_DIR_X64%\nucleus_clipboard_windows.dll
if exist "%OUT_DIR_ARM64%\nucleus_clipboard_windows.dll" echo   %OUT_DIR_ARM64%\nucleus_clipboard_windows.dll

if defined LOCALAPPDATA (
    set "CACHE_BASE=%LOCALAPPDATA%\nucleus\native"
    if exist "!CACHE_BASE!\win32-x64\nucleus_clipboard_windows.dll" (
        del /q "!CACHE_BASE!\win32-x64\nucleus_clipboard_windows.dll"
        echo Cleared x64 cache
    )
    if exist "!CACHE_BASE!\win32-aarch64\nucleus_clipboard_windows.dll" (
        del /q "!CACHE_BASE!\win32-aarch64\nucleus_clipboard_windows.dll"
        echo Cleared ARM64 cache
    )
)

endlocal
