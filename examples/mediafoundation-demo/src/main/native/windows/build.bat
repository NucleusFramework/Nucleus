@echo off
REM Builds nucleus_mf_video.dll for the Media Foundation video sample.
REM
REM Outputs in src/main/resources/nucleus/native/{win32-x64,win32-aarch64}/.
REM
REM Prerequisites:
REM   - Visual Studio 2019/2022 Build Tools with x64 (and ARM64) support
REM   - JAVA_HOME pointing to a JDK with include/jni.h and include/win32/
REM
REM Everything it links against (mfplat, mfreadwrite, d3d11) ships with Windows,
REM so there is nothing else to install. The library stays out of CI: it is
REM sample code, and the demo says so when it is missing.
REM
REM Usage: build.bat

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "SRC=%SCRIPT_DIR%nucleus_mf_video.cpp"
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

REM ---- Locate vcvarsall.bat (same resolution as the tao backend's script) ----
set "VCVARSALL="
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -prerelease -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do (
        if exist "%%i\VC\Auxiliary\Build\vcvarsall.bat" set "VCVARSALL=%%i\VC\Auxiliary\Build\vcvarsall.bat"
    )
)
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
    echo ERROR: Could not locate vcvarsall.bat. >&2
    exit /b 1
)
echo Using vcvarsall.bat: %VCVARSALL%

if not exist "%OUT_DIR_X64%" mkdir "%OUT_DIR_X64%"
if not exist "%OUT_DIR_ARM64%" mkdir "%OUT_DIR_ARM64%"

echo.
echo === Building nucleus_mf_video.dll (x64) ===
setlocal
call "%VCVARSALL%" x64
if errorlevel 1 (
    echo ERROR: vcvarsall x64 failed >&2
    exit /b 1
)
REM /MT: the static CRT keeps the sample DLL free of a redistributable.
cl /LD /O2 /MT /EHsc /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%SRC%" ^
    /Fe:"%OUT_DIR_X64%\nucleus_mf_video.dll"
if errorlevel 1 (
    echo ERROR: x64 compilation failed >&2
    exit /b 1
)
endlocal
del /q "%OUT_DIR_X64%\*.obj" "%OUT_DIR_X64%\*.lib" "%OUT_DIR_X64%\*.exp" 2>nul
del /q "%SCRIPT_DIR%*.obj" 2>nul

echo.
echo === Building nucleus_mf_video.dll (ARM64) ===
setlocal
call "%VCVARSALL%" x64_arm64
if errorlevel 1 (
    echo WARNING: vcvarsall x64_arm64 failed - skipping ARM64 >&2
    endlocal
    goto :clear_cache
)
cl /LD /O2 /MT /EHsc /GS- /nologo ^
    /I"%JNI_INCLUDE%" /I"%JNI_INCLUDE_WIN32%" ^
    "%SRC%" ^
    /Fe:"%OUT_DIR_ARM64%\nucleus_mf_video.dll"
if errorlevel 1 (
    echo WARNING: ARM64 compilation failed >&2
    endlocal
    goto :clear_cache
)
endlocal
del /q "%OUT_DIR_ARM64%\*.obj" "%OUT_DIR_ARM64%\*.lib" "%OUT_DIR_ARM64%\*.exp" 2>nul
del /q "%SCRIPT_DIR%*.obj" 2>nul

REM Per the module checklist: the loader serves its cached copy otherwise.
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
if exist "%OUT_DIR_X64%\nucleus_mf_video.dll"   echo   %OUT_DIR_X64%\nucleus_mf_video.dll
if exist "%OUT_DIR_ARM64%\nucleus_mf_video.dll" echo   %OUT_DIR_ARM64%\nucleus_mf_video.dll

exit /b 0
