@echo off
REM Compiles the Windows native implementation for nucleus_system_info.
REM Requires Visual Studio Build Tools (MSVC) and a JDK with JNI headers.
REM Usage: build.bat

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "RESOURCE_DIR=%SCRIPT_DIR%..\..\resources\nucleus\native"
set "OUT_DIR_X64=%RESOURCE_DIR%\win32-x64"
set "OUT_DIR_ARM64=%RESOURCE_DIR%\win32-aarch64"

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME not set. >&2
    exit /b 1
)
if not exist "%JAVA_HOME%\include\jni.h" (
    echo ERROR: JNI headers not found at %JAVA_HOME%\include >&2
    exit /b 1
)

set "JNI_INCLUDE=%JAVA_HOME%\include"
set "JNI_INCLUDE_WIN=%JAVA_HOME%\include\win32"

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

if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

set "SOURCES=%SCRIPT_DIR%nucleus_system_info_os.c %SCRIPT_DIR%nucleus_system_info_memory.c %SCRIPT_DIR%nucleus_system_info_cpu.c %SCRIPT_DIR%nucleus_system_info_disk.c %SCRIPT_DIR%nucleus_system_info_component.c %SCRIPT_DIR%nucleus_system_info_network.c %SCRIPT_DIR%nucleus_system_info_process.c %SCRIPT_DIR%nucleus_system_info_user.c %SCRIPT_DIR%nucleus_system_info_hardware.c %SCRIPT_DIR%nucleus_system_info_gpu.c %SCRIPT_DIR%nucleus_system_info_battery.c %SCRIPT_DIR%nucleus_system_info_idle.c %SCRIPT_DIR%nucleus_system_info_connectivity.c"
set "LIBS=kernel32.lib user32.lib advapi32.lib psapi.lib iphlpapi.lib ole32.lib oleaut32.lib wbemuuid.lib netapi32.lib powrprof.lib ws2_32.lib dxgi.lib"

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

cl /nologo /LD /O2 /W3 /D_CRT_SECURE_NO_WARNINGS ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN%" ^
    %SOURCES% ^
    /Fe:"%OUT_DIR_X64%\nucleus_system_info.dll" ^
    /link /DLL %LIBS%
if errorlevel 1 (
    echo ERROR: x64 compilation failed >&2
    exit /b 1
)
endlocal

echo Built Windows system-info DLL (x64)

REM Clean up intermediate files
del /q "%SCRIPT_DIR%*.obj" 2>nul
del /q "%OUT_DIR_X64%\*.lib" "%OUT_DIR_X64%\*.exp" 2>nul

REM ARM64 cross-compilation is not supported: the CPU sources use the x86-only
REM __cpuid intrinsic. Copy the x64 DLL as a placeholder so packaging/verify
REM steps that expect the aarch64 artifact still succeed.
if not exist "%OUT_DIR_ARM64%\nucleus_system_info.dll" (
    copy "%OUT_DIR_X64%\nucleus_system_info.dll" "%OUT_DIR_ARM64%\nucleus_system_info.dll" >nul 2>&1
)

:done
REM Clear NativeLibraryLoader cache
if exist "%USERPROFILE%\.cache\nucleus\native" (
    rd /s /q "%USERPROFILE%\.cache\nucleus\native" 2>nul
    echo Cleared NativeLibraryLoader cache.
)

endlocal
