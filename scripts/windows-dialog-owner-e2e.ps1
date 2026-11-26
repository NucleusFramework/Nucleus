# Windows E2E for #515: a DecoratedDialog open over its parent window.
#
# Launches examples/dialog-owner-demo, waits for the dialog, then raises the parent the two ways a
# user does — a click on the part of the parent the dialog leaves uncovered, and an activation from
# outside the process (what the taskbar button and Alt-Tab do) — and types after each. It reports the
# stacking order, the foreground window and which text field received the keystrokes.
#
# Usage (from the repo root, JDK 25 for Gradle):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\windows-dialog-owner-e2e.ps1
param(
    [int]$TimeoutSeconds = 60,
    # Reuse the classpath file of a previous run.
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class E2eWin32 {
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr FindWindowW(string cls, string title);
    [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr hwnd, uint cmd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, int dx, int dy, uint data, UIntPtr extra);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] public static extern IntPtr GetLastActivePopup(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(POINT p);
    [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassNameW(IntPtr hwnd, StringBuilder s, int n);

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }

    // Top-level window under a screen point, as `class@pid`, for reports.
    public static IntPtr TopLevelAt(int x, int y) {
        POINT p; p.X = x; p.Y = y;
        return GetAncestor(WindowFromPoint(p), 2);
    }

    public static uint PidOf(IntPtr hwnd) {
        uint pid; GetWindowThreadProcessId(hwnd, out pid);
        return pid;
    }

    public static string Describe(IntPtr hwnd) {
        uint pid; GetWindowThreadProcessId(hwnd, out pid);
        var sb = new StringBuilder(256); GetClassNameW(hwnd, sb, 256);
        return String.Format("0x{0:X} {1}@{2}", (long)hwnd, sb, pid);
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    public const uint GW_HWNDPREV = 3;
    public const uint GW_OWNER = 4;

    // Whether `upper` sits above `lower` in the z-order: walk up from `lower`.
    public static bool IsAbove(IntPtr upper, IntPtr lower) {
        for (IntPtr h = GetWindow(lower, GW_HWNDPREV); h != IntPtr.Zero; h = GetWindow(h, GW_HWNDPREV)) {
            if (h == upper) return true;
        }
        return false;
    }

    public static void Click(int x, int y) {
        SetCursorPos(x, y);
        System.Threading.Thread.Sleep(80);
        mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);
        mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);
    }

    public static void Type(string text) {
        foreach (char c in text) {
            byte vk = (byte)char.ToUpperInvariant(c);
            keybd_event(vk, 0, 0, UIntPtr.Zero);
            keybd_event(vk, 0, 2, UIntPtr.Zero);
            System.Threading.Thread.Sleep(40);
        }
    }

    // Activates `hwnd` from outside its process, as the shell does: a synthetic Alt press lifts
    // the foreground lock, so SetForegroundWindow is honoured.
    public static bool Activate(IntPtr hwnd) {
        keybd_event(0x12, 0, 0, UIntPtr.Zero);
        bool ok = SetForegroundWindow(hwnd);
        keybd_event(0x12, 0, 2, UIntPtr.Zero);
        return ok;
    }
}
'@

$repo = Split-Path -Parent $PSScriptRoot
if (-not $SkipBuild) {
    & (Join-Path $repo 'gradlew.bat') -p $repo ':examples:dialog-owner-demo:writeE2eClasspath' '--console=plain' '-q'
    if ($LASTEXITCODE -ne 0) { throw 'writeE2eClasspath failed' }
}
$classpath = Get-Content (Join-Path $repo 'examples\dialog-owner-demo\build\e2e-classpath.txt') -Raw
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$log = Join-Path $env:TEMP "dialog-owner-e2e-$PID.log"
$env:CLASSPATH = $classpath
$app = Start-Process -FilePath $java -ArgumentList 'dialogownerdemo.MainKt' `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -NoNewWindow

function Log-Lines { if (Test-Path $log) { Get-Content $log } else { @() } }
function Count-Typed([string]$prefix) { @(Log-Lines | Where-Object { $_ -like "E2E $prefix=*" }).Count }

$failures = @()
try {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $main = [IntPtr]::Zero
    $dialog = [IntPtr]::Zero
    while ((Get-Date) -lt $deadline) {
        $main = [E2eWin32]::FindWindowW([NullString]::Value, 'DialogOwnerDemo Main')
        $dialog = [E2eWin32]::FindWindowW([NullString]::Value, 'DialogOwnerDemo Dialog')
        if ($main -ne [IntPtr]::Zero -and $dialog -ne [IntPtr]::Zero -and (Log-Lines) -contains 'E2E dialog-shown') { break }
        Start-Sleep -Milliseconds 250
    }
    if ($dialog -eq [IntPtr]::Zero) { throw "dialog never appeared; log: $(Log-Lines)" }
    Start-Sleep -Milliseconds 800

    Write-Host ("app pid={3} main=0x{0:X} dialog=0x{1:X} owner(dialog)=0x{2:X}" -f `
        [int64]$main, [int64]$dialog, [int64][E2eWin32]::GetWindow($dialog, [E2eWin32]::GW_OWNER), $app.Id)

    function Report([string]$step) {
        $fg = [E2eWin32]::GetForegroundWindow()
        $who = if ($fg -eq $main) { 'main' } elseif ($fg -eq $dialog) { 'dialog' } else { [E2eWin32]::Describe($fg) }
        Write-Host ("[{0}] foreground={1} dialogAboveMain={2} mainEnabled={3} lastActivePopup(main)={4}" -f `
            $step, $who, [E2eWin32]::IsAbove($dialog, $main), [E2eWin32]::IsWindowEnabled($main),
            $(if ([E2eWin32]::GetLastActivePopup($main) -eq $dialog) { 'dialog' } else { 'main' }))
        return $who
    }

    function Type-Into([string]$text) {
        $mainBefore = Count-Typed 'main-text'
        $dialogBefore = Count-Typed 'dialog-text'
        [E2eWin32]::Type($text)
        Start-Sleep -Milliseconds 700
        return @{ Main = (Count-Typed 'main-text') - $mainBefore; Dialog = (Count-Typed 'dialog-text') - $dialogBefore }
    }

    # Raises the app with [E2eWin32]::Activate($dialog), runs $raiseMain, then checks where the
    # activation and the keystrokes went. Another process's window popping up in between (a headful
    # suite running on the same desktop) makes the attempt inconclusive, so it is retried.
    function Run-Step([string]$step, [string]$text, [scriptblock]$raiseMain) {
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $null = [E2eWin32]::Activate($dialog)
            Start-Sleep -Milliseconds 500
            if (-not (& $raiseMain)) { Write-Host "[$step] attempt $attempt disturbed before raising; retrying"; continue }
            Start-Sleep -Milliseconds 700
            $fg = [E2eWin32]::GetForegroundWindow()
            if ([E2eWin32]::PidOf($fg) -ne $app.Id) {
                Write-Host "[$step] attempt $attempt disturbed by $([E2eWin32]::Describe($fg)); retrying"
                continue
            }
            $who = Report $step
            $typed = Type-Into $text
            Write-Host "[$step] keystrokes: main=$($typed.Main) dialog=$($typed.Dialog)"
            if ($who -ne 'dialog') { $script:failures += "${step}: foreground is $who, not the dialog" }
            if (-not [E2eWin32]::IsAbove($dialog, $main)) { $script:failures += "${step}: dialog fell below its owner" }
            if ($typed.Main -gt 0) { $script:failures += "${step}: keystrokes reached the blocked main window" }
            if ($typed.Dialog -eq 0) { $script:failures += "${step}: the dialog did not receive the keystrokes" }
            return
        }
        $script:failures += "${step}: inconclusive, every attempt was disturbed by another process's window"
    }

    # 0. Baseline: the dialog has the keyboard. A process started from a script never gets the
    # foreground on its own, hence the activation inside Run-Step.
    Run-Step 'opened' 'ab' { $true }

    # 1. Click the part of the main window the dialog does not cover.
    Run-Step 'click-main' 'cd' {
        $r = New-Object E2eWin32+RECT
        $null = [E2eWin32]::GetWindowRect($main, [ref]$r)
        $x = $r.Left + 40
        $y = $r.Bottom - 40
        if ([E2eWin32]::TopLevelAt($x, $y) -ne $main) { return $false }
        [E2eWin32]::Click($x, $y)
        $true
    }

    # 2. Activate the main window from outside the process (taskbar button / Alt-Tab).
    Run-Step 'activate-main' 'ef' { $null = [E2eWin32]::Activate($main); $true }
} finally {
    Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL $_" }
    exit 1
}
Write-Host 'PASS'
