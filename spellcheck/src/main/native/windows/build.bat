@echo off
REM Compiles nucleus_spellcheck.cpp into per-architecture DLLs (x64 + ARM64).
REM The outputs are placed in the JAR resources so they ship with the library.
REM
REM Prerequisites: Visual Studio Build Tools (MSVC) with Windows SDK.
REM Usage: build.bat

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "SRC=%SCRIPT_DIR%nucleus_spellcheck.cpp"
set "RESOURCE_DIR=%SCRIPT_DIR%..\..\resources\nucleus\native"
set "OUT_DIR_X64=%RESOURCE_DIR%\win32-x64"
set "OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64"

REM Check JAVA_HOME
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

REM Locate vcvarsall.bat
set "VCVARSALL="
REM Prefer vswhere: resolves any installed VS version (incl. 18+ and previews).
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -prerelease -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
        if exist "%%i\VC\Auxiliary\Build\vcvarsall.bat" set "VCVARSALL=%%i\VC\Auxiliary\Build\vcvarsall.bat"
    )
)
REM Fallback: scan well-known install locations if vswhere did not resolve a path.
if "%VCVARSALL%"=="" (
    for %%v in (18 2022 2019 2017) do (
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
)
:found_vc
if "%VCVARSALL%"=="" (
    echo ERROR: Could not locate vcvarsall.bat. Install Visual Studio Build Tools. >&2
    exit /b 1
)

echo Using vcvarsall.bat: %VCVARSALL%

REM Create output directories
if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

REM ---- Compile x64 ----
REM Use setlocal/endlocal to isolate vcvarsall environment per architecture,
REM preventing PATH accumulation that exceeds cmd.exe line length on CI.
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
    /Fe:"%OUT_DIR_X64%\nucleus_spellcheck.dll" ^
    /link ole32.lib kernel32.lib
if errorlevel 1 (
    echo ERROR: x64 compilation failed >&2
    exit /b 1
)
endlocal

REM Clean up intermediate files
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
    /Fe:"%OUT_DIR_ARM64%\nucleus_spellcheck.dll" ^
    /link ole32.lib kernel32.lib
if errorlevel 1 (
    echo WARNING: ARM64 compilation failed. >&2
    endlocal
    goto :done
)
endlocal

REM Clean up intermediate files
del /q "%OUT_DIR_ARM64%\*.obj" "%OUT_DIR_ARM64%\*.lib" "%OUT_DIR_ARM64%\*.exp" 2>nul
del /q "%SCRIPT_DIR%\*.obj" 2>nul

:done
echo.
echo Built DLLs:
if exist "%OUT_DIR_X64%\nucleus_spellcheck.dll" echo   %OUT_DIR_X64%\nucleus_spellcheck.dll
if exist "%OUT_DIR_ARM64%\nucleus_spellcheck.dll" echo   %OUT_DIR_ARM64%\nucleus_spellcheck.dll

endlocal
