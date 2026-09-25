<#
.SYNOPSIS
  Windows E2E of screen-capture: runs examples/screen-capture-demo in self-test mode.

.DESCRIPTION
  The demo draws a pixel-exact pattern and checks window, display, region, occluded,
  minimized, own-UI-thread and blocked-UI-thread captures against it, then tortures the API
  from many threads. This script adds what the demo cannot do on its own:
    - parks the cursor at the centre of the primary display (cursor compositing check);
    - opens a window whose thread never pumps (a hung foreign window, PrintWindow's trap);
    - samples the demo's GDI / USER objects and kernel handles around the torture phase:
      a leak there grows with every capture.
  Exits with the number of failures.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\screen-capture-windows-e2e.ps1 -Torture 2000
#>
param(
    [int]$Torture = 800,
    [int]$TimeoutSeconds = 900,
    [string]$OutDir = (Join-Path $env:TEMP "screen-capture-e2e")
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ScE2e {
    [DllImport("user32.dll")] public static extern uint GetGuiResources(IntPtr process, uint flags);
    [DllImport("kernel32.dll")] public static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
    public static uint[] Gui(int pid) {
        IntPtr h = OpenProcess(0x1000 /* QUERY_LIMITED_INFORMATION */, false, pid);
        if (h == IntPtr.Zero) return new uint[] { 0, 0 };
        try { return new uint[] { GetGuiResources(h, 0), GetGuiResources(h, 1) }; } finally { CloseHandle(h); }
    }
}
"@

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$log = Join-Path $OutDir "selftest.log"
Remove-Item $log -ErrorAction SilentlyContinue

# A window whose thread spins without pumping messages: PrintWindow would wait on it forever.
$hungScript = Join-Path $OutDir "hung-window.ps1"
@'
Add-Type -AssemblyName System.Windows.Forms
$f = New-Object System.Windows.Forms.Form
$f.Text = "ScreenCaptureE2eHung"
$f.StartPosition = "Manual"
$f.WindowState = "Normal"
$f.Location = New-Object System.Drawing.Point(40, 40)
$f.BackColor = [System.Drawing.Color]::FromArgb(255, 20, 160, 60)
$f.Show()
$f.WindowState = "Normal"
[System.Windows.Forms.Application]::DoEvents()
Start-Sleep -Milliseconds 200
[System.Windows.Forms.Application]::DoEvents()
[Int64]$f.Handle | Set-Content -Encoding ASCII $args[0]
$end = [DateTime]::Now.AddSeconds(600)
while ([DateTime]::Now -lt $end) { }
'@ | Set-Content -Encoding ASCII $hungScript
$hwndFile = Join-Path $OutDir "hung-window.hwnd"
Remove-Item $hwndFile -ErrorAction SilentlyContinue
$hung = Start-Process powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $hungScript, $hwndFile -PassThru -WindowStyle Minimized
$hwnd = 0
for ($i = 0; $i -lt 150 -and $hwnd -eq 0; $i++) {
    Start-Sleep -Milliseconds 200
    if (Test-Path $hwndFile) { $hwnd = [Int64](Get-Content $hwndFile -Raw).Trim() }
}
Write-Host "hung window: $hwnd (pid $($hung.Id))"

$primary = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
[System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point(
    ($primary.X + [int]($primary.Width / 2)), ($primary.Y + [int]($primary.Height / 2)))

$env:SCREEN_CAPTURE_DEMO_SELFTEST = "1"
$env:SCREEN_CAPTURE_DEMO_LOG = $log
$env:SCREEN_CAPTURE_DEMO_OUT = $OutDir
$env:SCREEN_CAPTURE_DEMO_TORTURE = "$Torture"
$env:SCREEN_CAPTURE_DEMO_HUNG_HWND = "$hwnd"
# The parked cursor shows (it may be moved since: the check finds it wherever it is).
$env:SCREEN_CAPTURE_DEMO_CURSOR_EXPECTED = "1"

$gradle = Start-Process -FilePath (Join-Path $root "gradlew.bat") `
    -ArgumentList ":examples:screen-capture-demo:run", "--console=plain", "-q" `
    -WorkingDirectory $root -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $OutDir "gradle.out") -RedirectStandardError (Join-Path $OutDir "gradle.err")

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$appPid = 0
$before = $null
$after = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    if (-not (Test-Path $log)) { if ($gradle.HasExited) { break } else { continue } }
    $text = Get-Content $log -Raw
    if ($appPid -eq 0 -and $text -match "START pid=(\d+)") { $appPid = [int]$Matches[1] }
    if ($appPid -ne 0 -and $null -eq $before -and $text -match "PHASE torture-begin") {
        Start-Sleep -Milliseconds 1000
        $gui = [ScE2e]::Gui($appPid)
        $before = @{ gdi = $gui[0]; user = $gui[1]; handles = (Get-Process -Id $appPid).HandleCount }
    }
    if ($appPid -ne 0 -and $null -eq $after -and $text -match "PHASE torture-end") {
        Start-Sleep -Milliseconds 1500
        $gui = [ScE2e]::Gui($appPid)
        $after = @{ gdi = $gui[0]; user = $gui[1]; handles = (Get-Process -Id $appPid).HandleCount }
    }
    if ($text -match "DONE failures=") { break }
}
$gradle.WaitForExit(120000) | Out-Null
Stop-Process -Id $hung.Id -Force -ErrorAction SilentlyContinue

$failures = 0
if (Test-Path $log) {
    $lines = Get-Content $log
    $lines | Where-Object { $_ -match " (FAIL|INFO|SKIP|DISPLAY) " } | ForEach-Object { Write-Host $_ }
    $failures += ($lines | Where-Object { $_ -match " FAIL " }).Count
    Write-Host ("passed checks: " + ($lines | Where-Object { $_ -match " PASS " }).Count)
    if (-not ($lines -match "DONE failures=")) { Write-Host "FAIL self-test did not finish"; $failures++ }
} else {
    Write-Host "FAIL no self-test log"; $failures++
}

if ($null -ne $before -and $null -ne $after) {
    Write-Host ("resources before torture: gdi={0} user={1} handles={2}" -f $before.gdi, $before.user, $before.handles)
    Write-Host ("resources after torture:  gdi={0} user={1} handles={2}" -f $after.gdi, $after.user, $after.handles)
    # Threads, sockets and JIT come and go; a per-capture leak would be thousands.
    if ($after.gdi - $before.gdi -gt 20) { Write-Host "FAIL GDI objects leak"; $failures++ }
    if ($after.user - $before.user -gt 20) { Write-Host "FAIL USER objects leak"; $failures++ }
    if ($after.handles - $before.handles -gt 200) { Write-Host "FAIL kernel handles leak"; $failures++ }
} else {
    Write-Host "FAIL resources were not sampled"; $failures++
}

Write-Host "failures=$failures"
exit $failures
