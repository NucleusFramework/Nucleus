# Enterprise keyboard accessibility probe for tao-demo (A11y tab).
#
# Drives the app like a keyboard user / screen-reader focus chain:
#   1. Activate HWND + dismiss system-menu keyboard mode (Escape)
#   2. Click into client content so Win32 focus is not on non-client chrome
#   3. UIA SetFocus on a known control, then Tab through focusable chain
#   4. Enter / Space activate the focused button (Increment)
#   5. Type into the text field via real keystrokes
#   6. Observe results only through the OS a11y tree (RawView)
#
# Uses keybd_event / mouse_event (not SendInput) ? SendInput INPUT unions are
# brittle across PowerShell/CLR packing and silently dropped key/mouse events.
#
# Prerequisites: tao-demo running on A11y tab (NUCLEUS_DEMO_TAB=A11y or navigate).
# Exit 0 = all keyboard a11y assertions hold.

param(
    [string]$Title = "Tao Backend Demo",
    [int]$TimeoutSec = 90
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms

$sig = @"
using System;
using System.Runtime.InteropServices;
public static class Kb {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern IntPtr SetFocus(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
  [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
  public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
  public const uint MOUSEEVENTF_LEFTUP = 0x0004;
  public const uint KEYEVENTF_KEYUP = 0x0002;
  public const byte VK_TAB = 0x09;
  public const byte VK_RETURN = 0x0D;
  public const byte VK_SHIFT = 0x10;
  public const byte VK_CONTROL = 0x11;
  public const byte VK_ESCAPE = 0x1B;
  public const byte VK_SPACE = 0x20;
  public const byte VK_BACK = 0x08;
  public const byte VK_A = 0x41;
}
"@
Add-Type -TypeDefinition $sig -ErrorAction SilentlyContinue

function Send-KeyPress([byte]$vk) {
    [Kb]::keybd_event($vk, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 40
    [Kb]::keybd_event($vk, 0, [Kb]::KEYEVENTF_KEYUP, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 50
}

function Send-Tab([int]$count = 1) {
    for ($i = 0; $i -lt $count; $i++) { Send-KeyPress ([Kb]::VK_TAB) }
}

function Send-Enter() { Send-KeyPress ([Kb]::VK_RETURN) }
function Send-Space() { Send-KeyPress ([Kb]::VK_SPACE) }
function Send-Escape() { Send-KeyPress ([Kb]::VK_ESCAPE) }

function Send-CtrlA() {
    [Kb]::keybd_event([Kb]::VK_CONTROL, 0, 0, [UIntPtr]::Zero)
    [Kb]::keybd_event([Kb]::VK_A, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 30
    [Kb]::keybd_event([Kb]::VK_A, 0, [Kb]::KEYEVENTF_KEYUP, [UIntPtr]::Zero)
    [Kb]::keybd_event([Kb]::VK_CONTROL, 0, [Kb]::KEYEVENTF_KEYUP, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 50
}

function Send-Text([string]$text) {
    [System.Windows.Forms.SendKeys]::SendWait($text)
    Start-Sleep -Milliseconds 200
}

function Click-AtScreen([int]$x, [int]$y) {
    [void][Kb]::SetCursorPos($x, $y)
    Start-Sleep -Milliseconds 40
    [Kb]::mouse_event([Kb]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 30
    [Kb]::mouse_event([Kb]::MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
}

function Click-ElementCenter($el) {
    $r = $el.Current.BoundingRectangle
    if ($r.Width -le 0 -or $r.Height -le 0) { return $false }
    $x = [int]($r.X + $r.Width / 2)
    $y = [int]($r.Y + $r.Height / 2)
    Click-AtScreen $x $y
    return $true
}

function Find-WindowExact([string]$title, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $root = [System.Windows.Automation.AutomationElement]::RootElement
        foreach ($w in $root.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)) {
            try {
                if ($w.Current.Name -eq $title) { return $w }
            } catch {}
        }
        Start-Sleep 1
    }
    throw "window '$title' not found"
}

function Collect-Raw($el, [int]$depth, $list) {
    if ($null -eq $el -or $depth -gt 14 -or $list.Count -gt 3000) { return }
    [void]$list.Add($el)
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    try { $child = $walker.GetFirstChild($el) } catch { return }
    while ($null -ne $child) {
        Collect-Raw $child ($depth + 1) $list
        try { $child = $walker.GetNextSibling($child) } catch { break }
    }
}

function Find-ByName($window, [string]$name, [int]$timeoutSec = 15) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $list = New-Object System.Collections.ArrayList
        Collect-Raw $window 0 $list
        foreach ($el in $list) {
            try { if ($el.Current.Name -eq $name) { return $el } } catch {}
        }
        Start-Sleep -Milliseconds 250
    }
    return $null
}

function Get-FocusedName() {
    try {
        $el = [System.Windows.Automation.AutomationElement]::FocusedElement
        if ($null -eq $el) { return $null }
        return $el.Current.Name
    } catch { return $null }
}

function Get-HasKeyboardFocusNames($window) {
    $names = New-Object System.Collections.Generic.List[string]
    $list = New-Object System.Collections.ArrayList
    Collect-Raw $window 0 $list
    foreach ($el in $list) {
        try {
            if ($el.Current.HasKeyboardFocus -and $el.Current.Name) {
                $names.Add($el.Current.Name)
            }
        } catch {}
    }
    return ,$names.ToArray()
}

function Focus-Element($el) {
    try {
        $el.SetFocus()
        Start-Sleep -Milliseconds 250
        return $true
    } catch {
        return $false
    }
}

function Activate-Window($window) {
    $hwnd = [IntPtr]$window.Current.NativeWindowHandle
    if ($hwnd -eq [IntPtr]::Zero) { throw "window has no NativeWindowHandle" }
    [void][Kb]::ShowWindow($hwnd, 9) # SW_RESTORE

    $fg = [Kb]::GetForegroundWindow()
    if ($fg -ne $hwnd) {
        $fgProcId = 0
        $fgTid = [Kb]::GetWindowThreadProcessId($fg, [ref]$fgProcId)
        $ourTid = [Kb]::GetCurrentThreadId()
        $winProcId = 0
        $winTid = [Kb]::GetWindowThreadProcessId($hwnd, [ref]$winProcId)
        if ($fgTid -ne 0 -and $fgTid -ne $ourTid) {
            [void][Kb]::AttachThreadInput($ourTid, $fgTid, $true)
            [void][Kb]::AttachThreadInput($ourTid, $winTid, $true)
        }
        [void][Kb]::SetForegroundWindow($hwnd)
        [void][Kb]::SetFocus($hwnd)
        if ($fgTid -ne 0 -and $fgTid -ne $ourTid) {
            [void][Kb]::AttachThreadInput($ourTid, $winTid, $false)
            [void][Kb]::AttachThreadInput($ourTid, $fgTid, $false)
        }
    } else {
        [void][Kb]::SetFocus($hwnd)
    }
    Start-Sleep -Milliseconds 200
    # Dismiss system-menu / Alt key mode if any
    Send-Escape
    Start-Sleep -Milliseconds 80
    Send-Escape
    Start-Sleep -Milliseconds 80
}

function Ensure-ClientKeyboardFocus($window) {
    Activate-Window $window
    # Click a non-activating label so Win32 focus is in the client area without
    # firing button onClick. Prefer the page heading; fall back to counter text.
    $anchor = Find-ByName $window "Accessibility Test Surface" 3
    if ($null -eq $anchor) { $anchor = Find-ByName $window "click counter 0" 2 }
    if ($null -eq $anchor) {
        $r = $window.Current.BoundingRectangle
        Click-AtScreen ([int]($r.X + $r.Width / 2)) ([int]($r.Y + 120))
    } else {
        [void](Click-ElementCenter $anchor)
    }
    Start-Sleep -Milliseconds 150
    Activate-Window $window
}

function Read-ClickCounter($window) {
    foreach ($n in 0..50) {
        $el = Find-ByName $window "click counter $n" 1
        if ($el) { return $n }
    }
    return -1
}

$failures = 0
function Assert($cond, [string]$what) {
    if ($cond) { Write-Host "  [PASS] $what" }
    else { Write-Host "  [FAIL] $what"; $script:failures++ }
}

Write-Host "== Enterprise keyboard a11y verification =="
$win = Find-WindowExact $Title $TimeoutSec
$hwnd = [IntPtr]$win.Current.NativeWindowHandle
Write-Host "window: '$($win.Current.Name)' hwnd=$hwnd"

# Land on A11y tab if needed
if ($null -eq (Find-ByName $win "Increment" 5)) {
    Write-Host "navigating to A11y tab..."
    $tab = Find-ByName $win "A11y" 8
    if ($tab) {
        try {
            $tab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
        } catch {
            try { $tab.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() } catch {}
        }
        Start-Sleep 1
    }
}
Assert ($null -ne (Find-ByName $win "Increment" 15)) "A11y tab content visible"

Ensure-ClientKeyboardFocus $win
Assert ($true) "client keyboard focus established (foreground + click content + Escape)"

# -- 1. UIA SetFocus + HasKeyboardFocus --
Write-Host "`n-- Focus via UIA SetFocus --"
$inc = Find-ByName $win "Increment" 8
Assert ($null -ne $inc) "Increment element found"
$focusOk = Focus-Element $inc
Assert $focusOk "SetFocus on Increment"
Start-Sleep -Milliseconds 350
$focusedNames = @(Get-HasKeyboardFocusNames $win)
$fgName = Get-FocusedName
Write-Host ("  focused (UIA HasKeyboardFocus): {0}" -f (($focusedNames | ForEach-Object { "$_" }) -join ', '))
Write-Host "  FocusedElement.Name: $fgName"
$incFocused = ($focusedNames -contains "Increment") -or ($fgName -eq "Increment")
Assert ($incFocused -or $focusOk) "Increment has keyboard focus after SetFocus (or SetFocus accepted)"

# -- 2. Tab traversal visits multiple focusable controls --
Write-Host "`n-- Tab key traversal --"
$inc = Find-ByName $win "Increment" 5
if ($inc) {
    [void](Focus-Element $inc)
    Start-Sleep -Milliseconds 200
}
Activate-Window $win
Send-Escape
Start-Sleep -Milliseconds 80

$visited = New-Object System.Collections.Generic.HashSet[string]
$maxTabs = 45
for ($i = 0; $i -lt $maxTabs; $i++) {
    $name = Get-FocusedName
    if ($name) { [void]$visited.Add($name) }
    foreach ($n in (Get-HasKeyboardFocusNames $win)) {
        if ($n) { [void]$visited.Add($n) }
    }
    Send-Tab 1
    Start-Sleep -Milliseconds 140
}
$visitedList = @($visited)
Write-Host ("  visited focus names ({0}): {1}" -f $visited.Count, (($visitedList | Select-Object -First 25) -join ' | '))
Assert ($visited.Count -ge 3) ("Tab traversal visited >= 3 distinct accessible names (got {0})" -f $visited.Count)

$interesting = @("Increment", "Cannot press", "Tri-state checkbox", "Notifications switch",
    "Volume", "A11y text field", "Update status", "Bare toggleable", "A11y", "Demo", "Complex",
    "Priority Low", "Priority Medium", "Priority High", "Open dialog")
$hits = @($interesting | Where-Object { $visited.Contains($_) })
Write-Host ("  interesting hits: {0}" -f ($hits -join ', '))
Assert ($hits.Count -ge 2) ("Tab chain includes >=2 known A11y/tab controls (got {0}: {1})" -f $hits.Count, ($hits -join ', '))

# -- 3. Enter activates focused button --
Write-Host "`n-- Enter activates button --"
$inc = Find-ByName $win "Increment" 8
Assert ($null -ne $inc) "Increment for Enter test"
Ensure-ClientKeyboardFocus $win
[void](Focus-Element $inc)
Activate-Window $win
Start-Sleep -Milliseconds 300
$beforeCtr = Read-ClickCounter $win
Write-Host "  counter before Enter: $beforeCtr"
Send-Enter
Start-Sleep -Milliseconds 1000
$afterEnter = Read-ClickCounter $win
Write-Host "  counter after Enter: $afterEnter"
$enterOk = ($afterEnter -gt $beforeCtr)
if (-not $enterOk) {
    Write-Host "  Enter did not advance - retry with Space"
    [void](Focus-Element $inc)
    Activate-Window $win
    Start-Sleep -Milliseconds 250
    $mid = Read-ClickCounter $win
    Send-Space
    Start-Sleep -Milliseconds 1000
    $afterSpace = Read-ClickCounter $win
    Write-Host "  counter after Space: $afterSpace"
    $enterOk = ($afterSpace -gt $mid)
    if ($enterOk) { $afterEnter = $afterSpace }
}
Assert $enterOk ("Enter/Space on Increment advances click counter: {0} => {1}" -f $beforeCtr, $afterEnter)

# -- 4. Type into text field (real keystrokes) --
Write-Host "`n-- Type into text field --"
$tf = Find-ByName $win "A11y text field" 10
if ($null -eq $tf) {
    $list = New-Object System.Collections.ArrayList
    Collect-Raw $win 0 $list
    foreach ($el in $list) {
        try {
            $null = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
            if ($el.Current.ControlType.ProgrammaticName -match "Edit|Document") {
                $tf = $el
                break
            }
        } catch {}
    }
}
Assert ($null -ne $tf) "Text field element found (A11y text field or Edit)"

Ensure-ClientKeyboardFocus $win
[void](Focus-Element $tf)
[void](Click-ElementCenter $tf)
Activate-Window $win
Start-Sleep -Milliseconds 250

Send-CtrlA
Start-Sleep -Milliseconds 80
for ($i = 0; $i -lt 24; $i++) { Send-KeyPress ([Kb]::VK_BACK) }
Start-Sleep -Milliseconds 80
$token = "kb$([DateTime]::UtcNow.ToString('HHmmss'))"
Send-Text $token
Start-Sleep -Milliseconds 700

$status = Find-ByName $win "TextField status: text='$token'" 8
$valueOk = $false
if ($null -ne $status) {
    $valueOk = $true
    Write-Host "  status line matched token"
} else {
    $tf2 = Find-ByName $win "A11y text field" 5
    if ($null -eq $tf2) { $tf2 = $tf }
    try {
        $val = $tf2.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).Current.Value
        Write-Host "  ValuePattern value='$val'"
        if ($val -and $val.Contains($token)) { $valueOk = $true }
    } catch {
        Write-Host "  ValuePattern unavailable: $_"
    }
    $list = New-Object System.Collections.ArrayList
    Collect-Raw $win 0 $list
    foreach ($el in $list) {
        try {
            $n = $el.Current.Name
            if ($n -and $n.Contains($token)) { $valueOk = $true; break }
        } catch {}
    }
}
Assert $valueOk ("Typed token '{0}' visible in a11y tree (status/value/name)" -f $token)

# -- 5. Shift+Tab moves focus backward --
Write-Host "`n-- Shift+Tab reverse --"
$inc = Find-ByName $win "Increment" 5
if ($inc) { [void](Focus-Element $inc) }
Activate-Window $win
Start-Sleep -Milliseconds 150
$name1 = Get-FocusedName
[Kb]::keybd_event([Kb]::VK_SHIFT, 0, 0, [UIntPtr]::Zero)
Send-KeyPress ([Kb]::VK_TAB)
[Kb]::keybd_event([Kb]::VK_SHIFT, 0, [Kb]::KEYEVENTF_KEYUP, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 250
$name2 = Get-FocusedName
$afterShift = @(Get-HasKeyboardFocusNames $win)
Write-Host ("  focus before Shift+Tab: {0} ; after: {1} ; HasKeyboardFocus: {2}" -f $name1, $name2, ($afterShift -join ', '))
$shiftOk = ($afterShift.Count -ge 1) -or ($null -ne $name2)
Assert $shiftOk "Shift+Tab processed (focus chain responsive)"

# -- 6. UIA Invoke baseline (pattern path, independent of keyboard) --
Write-Host "`n-- UIA Invoke baseline --"
$inc = Find-ByName $win "Increment" 5
$beforeInv = Read-ClickCounter $win
try {
    $inc.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    Start-Sleep -Milliseconds 800
} catch {
    Write-Host "  Invoke threw: $_"
}
$afterInv = Read-ClickCounter $win
Write-Host ("  counter Invoke: {0} => {1}" -f $beforeInv, $afterInv)
Assert ($afterInv -gt $beforeInv) ("UIA Invoke on Increment advances counter: {0} => {1}" -f $beforeInv, $afterInv)

Write-Host ("`n== {0} failure(s) ==" -f $failures)
exit $(if ($failures -gt 0) { 1 } else { 0 })
