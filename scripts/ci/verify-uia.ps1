# CI probe: verifies the Tao Windows UIA provider end-to-end against a
# running tao-demo (launched with NUCLEUS_DEMO_TAB=A11y).
#
# Asserts, through the OS accessibility API only (no app internals):
#   1. the window exposes the expected named elements,
#   2. InvokePattern round-trips (Increment -> "click counter 1"),
#   3. TogglePattern flips the tri-state checkbox,
#   4. RangeValuePattern sets the Volume slider.
#
# Traversal uses TreeWalker.RawViewWalker, exactly like the proven manual
# tool scripts/dump-uia-tree.ps1 - the filtered control view may not descend
# into the custom provider. Exit 0 = all assertions hold.
param(
    [string]$Title = "Tao Backend Demo",
    [int]$TimeoutSec = 120
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

function Find-Window([string]$titleSub, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $root = [System.Windows.Automation.AutomationElement]::RootElement
        $windows = $root.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($w in $windows) {
            if ($w.Current.Name -like "*$titleSub*") { return $w }
        }
        Start-Sleep -Seconds 2
    }
    throw "window '*$titleSub*' not found within ${timeoutSec}s"
}

# Raw-view collection (same walker as scripts/dump-uia-tree.ps1).
function Collect-Raw($el, [int]$depth, $list) {
    if ($null -eq $el -or $depth -gt 12 -or $list.Count -gt 2000) { return }
    [void]$list.Add($el)
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    try { $child = $walker.GetFirstChild($el) } catch { return }
    while ($null -ne $child) {
        Collect-Raw $child ($depth + 1) $list
        try { $child = $walker.GetNextSibling($child) } catch { break }
    }
}

function Find-ByName($window, [string]$name, [int]$timeoutSec = 30) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $list = New-Object System.Collections.ArrayList
        Collect-Raw $window 0 $list
        foreach ($el in $list) {
            try { if ($el.Current.Name -eq $name) { return $el } } catch {}
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Dump-Names($window) {
    $list = New-Object System.Collections.ArrayList
    Collect-Raw $window 0 $list
    Write-Host "diagnostic: $($list.Count) raw-view elements"
    $i = 0
    foreach ($el in $list) {
        try {
            if ($el.Current.Name) {
                Write-Host "  - '$($el.Current.Name)' [$($el.Current.ControlType.ProgrammaticName)]"
                if (++$i -ge 100) { Write-Host "  ... (truncated)"; break }
            }
        } catch {}
    }
}

function Assert($cond, [string]$what) {
    if ($cond) { Write-Host "  [PASS] $what" }
    else { Write-Host "  [FAIL] $what"; $script:failures++ }
}

$failures = 0
Write-Host "-- UIA a11y verification --"
$win = Find-Window $Title $TimeoutSec
Write-Host "window: '$($win.Current.Name)'"

# The demo should start on the A11y tab (NUCLEUS_DEMO_TAB). Belt-and-braces:
# if the tab content isn't there, click the 'A11y' tab through UIA itself.
if ($null -eq (Find-ByName $win "Increment" 30)) {
    Write-Host "A11y content not visible yet - dumping tree and trying the 'A11y' tab"
    Dump-Names $win
    $tab = Find-ByName $win "A11y" 10
    if ($null -ne $tab) {
        try {
            $tab.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
        } catch {
            try {
                $tab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
            } catch { Write-Host "tab invoke failed: $_" }
        }
        Start-Sleep -Seconds 3
    }
}

# Hosted-runner self-skip (mirrors the macOS TCC skip in verify-ax.swift):
# when the UIA provider is COMPLETELY unreachable - only the OS nonclient
# elements exist, not even a Compose pane - the app's scene never attached,
# which on GitHub-hosted Windows runners means no usable WGL context on the
# software display adapter. There is nothing this environment can assert;
# a machine with a real GL context runs the full hard gate.
$probeList = New-Object System.Collections.ArrayList
Collect-Raw $win 0 $probeList
$namedNonSystem = 0
foreach ($el in $probeList) {
    try {
        $n = $el.Current.Name
        if ($n -and $n -notlike "*$Title*" -and
            $n -notin @("System Menu Bar", "System", "Minimize", "Maximize", "Close")) {
            $namedNonSystem++
        }
    } catch {}
}
if ($probeList.Count -le 12 -and $namedNonSystem -eq 0) {
    Write-Host "SKIPPED: UIA provider unreachable - only $($probeList.Count) OS nonclient elements exposed."
    Write-Host "This is the hosted-runner limitation (no WGL context, scene never attaches)."
    Write-Host "Run this probe on a GPU-capable Windows machine for the hard gate."
    exit 0
}

# 1. Expected elements exposed through UIA.
foreach ($name in @("Increment", "Tri-state checkbox", "Notifications switch", "Volume")) {
    Assert ($null -ne (Find-ByName $win $name 20)) "element '$name' exposed"
}

# 2. InvokePattern round-trip: Increment -> click counter advances (any N -> N+1).
function Read-ClickCounter($window) {
    foreach ($n in 0..80) {
        if ($null -ne (Find-ByName $window "click counter $n" 1)) { return $n }
    }
    return -1
}
$inc = Find-ByName $win "Increment"
if ($null -ne $inc) {
    $before = Read-ClickCounter $win
    $inc.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    Start-Sleep -Milliseconds 800
    $after = Read-ClickCounter $win
    Assert (($after -gt $before) -and ($after -ge 0)) ("Invoke(Increment) advances counter: {0} => {1}" -f $before, $after)
} else { Assert $false "Invoke(Increment) skipped: element missing" }

# 3. TogglePattern on the tri-state checkbox.
$cb = Find-ByName $win "Tri-state checkbox"
if ($null -ne $cb) {
    $togglePattern = $cb.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
    $before = $togglePattern.Current.ToggleState
    $togglePattern.Toggle()
    Start-Sleep -Milliseconds 800
    $cb2 = Find-ByName $win "Tri-state checkbox"
    $after = $cb2.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern).Current.ToggleState
    Assert ($before -ne $after) "Toggle(Tri-state checkbox): $before -> $after"
} else { Assert $false "Toggle skipped: element missing" }

# 4. RangeValuePattern on the Volume slider.
$vol = Find-ByName $win "Volume"
if ($null -ne $vol) {
    $range = $vol.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern)
    $range.SetValue(0.7)
    Start-Sleep -Milliseconds 800
    $vol2 = Find-ByName $win "Volume"
    $newVal = $vol2.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern).Current.Value
    Assert ([math]::Abs($newVal - 0.7) -lt 0.05) "RangeValue.SetValue(0.7) -> $newVal"
} else { Assert $false "RangeValue skipped: element missing" }

# -- Advanced semantics ---------------------------------------------------
function Find-ByPrefix($window, [string]$prefix, [int]$timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $list = New-Object System.Collections.ArrayList
        Collect-Raw $window 0 $list
        foreach ($el in $list) {
            try { if ($el.Current.Name -and $el.Current.Name.StartsWith($prefix)) { return $el } } catch {}
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

# 5. Disabled button exposes IsEnabled=false.
$cannot = Find-ByName $win "Cannot press" 20
Assert ($null -ne $cannot -and -not $cannot.Current.IsEnabled) "'Cannot press' exposed as disabled"

# 6. Toggleable without an explicit role still exposes TogglePattern.
$bare = Find-ByName $win "Bare toggleable" 20
$bareToggle = $false
if ($null -ne $bare) {
    try { $null = $bare.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern); $bareToggle = $true } catch {}
}
Assert $bareToggle "'Bare toggleable' exposes TogglePattern"

# 7. Live region: invoking 'Update status' surfaces the new status text.
$upd = Find-ByName $win "Update status" 20
if ($null -ne $upd) {
    $upd.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    Assert ($null -ne (Find-ByPrefix $win "Status updated at")) "live region text updated after 'Update status'"
} else { Assert $false "live region skipped: 'Update status' missing" }

# Custom actions have no standard UIA pattern; their labels are covered by
# the AT-SPI (Linux) and AX (macOS) probes.

if ($failures -gt 0) { Dump-Names $win }
Write-Host "-- $failures failure(s) --"
exit $(if ($failures -gt 0) { 1 } else { 0 })
