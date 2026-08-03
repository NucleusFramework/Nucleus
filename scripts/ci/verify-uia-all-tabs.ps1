# Enterprise multi-tab UIA probe for tao-demo.
#
# Walks every main navigation tab via RawView, asserts key accessible names
# exist, and exercises activation patterns (Invoke / Toggle / ExpandCollapse /
# SelectionItem / RangeValue) where applicable. Does NOT require
# NUCLEUS_DEMO_TAB - switches tabs through UIA like a real AT.
#
# Prerequisites: tao-demo running, window title "Tao Backend Demo".
# Exit 0 = all tab trees navigable + key actions succeed.

param(
    [string]$Title = "Tao Backend Demo",
    [int]$TimeoutSec = 90
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

function Find-Window([string]$titleExact, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $root = [System.Windows.Automation.AutomationElement]::RootElement
        foreach ($w in $root.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)) {
            try { if ($w.Current.Name -eq $titleExact) { return $w } } catch {}
        }
        Start-Sleep 1
    }
    throw "window '$titleExact' not found"
}

function Collect-Raw($el, [int]$depth, $list) {
    if ($null -eq $el -or $depth -gt 16 -or $list.Count -gt 4000) { return }
    [void]$list.Add($el)
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    try { $child = $walker.GetFirstChild($el) } catch { return }
    while ($null -ne $child) {
        Collect-Raw $child ($depth + 1) $list
        try { $child = $walker.GetNextSibling($child) } catch { break }
    }
}

function All-Named($window) {
    $list = New-Object System.Collections.ArrayList
    Collect-Raw $window 0 $list
    $names = New-Object System.Collections.Generic.List[string]
    foreach ($el in $list) {
        try {
            $n = $el.Current.Name
            if ($n) { [void]$names.Add($n) }
        } catch {}
    }
    return $names
}

function Find-ByName($window, [string]$name, [int]$timeoutSec = 12) {
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

function Find-ByNameContains($window, [string]$sub) {
    $list = New-Object System.Collections.ArrayList
    Collect-Raw $window 0 $list
    foreach ($el in $list) {
        try {
            $n = $el.Current.Name
            if ($n -and $n.ToLower().Contains($sub.ToLower())) { return $el }
        } catch {}
    }
    return $null
}

function Select-Tab($window, [string]$tabName) {
    $tab = Find-ByName $window $tabName 8
    if ($null -eq $tab) { throw "tab '$tabName' not found in tree" }
    $ok = $false
    try {
        $tab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
        $ok = $true
    } catch {}
    if (-not $ok) {
        try {
            $tab.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
            $ok = $true
        } catch {}
    }
    if (-not $ok) {
        # Fallback: clickable without pattern (legacy TabBar) - try Toggle
        try {
            $tab.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern).Toggle()
            $ok = $true
        } catch {}
    }
    if (-not $ok) { throw "cannot activate tab '$tabName' via SelectionItem/Invoke/Toggle" }
    Start-Sleep -Milliseconds 800
}

function Try-Invoke($el) {
    try {
        $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
        return $true
    } catch { return $false }
}

function Try-ExpandCollapse($el) {
    try {
        $p = $el.GetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern)
        $st = $p.Current.ExpandCollapseState
        if ($st -eq [System.Windows.Automation.ExpandCollapseState]::Collapsed) {
            $p.Expand()
        } else {
            $p.Collapse()
        }
        return $true
    } catch { return $false }
}

function Try-Toggle($el) {
    try {
        $p = $el.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
        $before = $p.Current.ToggleState
        $p.Toggle()
        Start-Sleep -Milliseconds 600
        $p2 = $el.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
        return ($before -ne $p2.Current.ToggleState)
    } catch {
        # element may be recycled - re-find not available here
        return $false
    }
}

$failures = 0
function Assert($cond, [string]$what) {
    if ($cond) { Write-Host "  [PASS] $what" }
    else { Write-Host "  [FAIL] $what"; $script:failures++ }
}

Write-Host "== Enterprise multi-tab UIA verification =="
$win = Find-Window $Title $TimeoutSec
Write-Host "window: '$($win.Current.Name)'"

# -- Tab bar itself --------------------------------------------------------
$tabLabels = @(
    "Demo", "Scroll", "Zoom", "Window actions", "A11y", "Complex",
    "Events", "WebView", "SwiftUI", "Texture"
)
Write-Host "`n-- Tab bar --"
foreach ($t in $tabLabels) {
    Assert ($null -ne (Find-ByName $win $t 6)) "tab '$t' exposed"
}

# Per-tab expectations: names that must appear after selecting the tab.
$tabChecks = [ordered]@{
    "Demo"            = @{ must = @("Demo"); minNamed = 8; action = $null }
    "Scroll"          = @{ must = @("Scroll"); minNamed = 15; action = $null }  # many list rows
    "Zoom"            = @{ must = @("Zoom"); minNamed = 8; action = $null }
    "Window actions"  = @{ must = @("Window actions"); minNamed = 10; action = $null }
    "A11y"            = @{
        must   = @("Increment", "Tri-state checkbox", "Notifications switch", "Volume", "Cannot press")
        minNamed = 25
        action = "a11y"
    }
    "Complex"         = @{
        must   = @("Add item", "Clear done", "Reset", "Buy milk", "Start")
        minNamed = 30
        action = "complex"
    }
    "Events"          = @{ must = @("Events"); minNamed = 8; action = $null }
    "WebView"         = @{ must = @("WebView"); minNamed = 6; action = $null }
    "SwiftUI"         = @{ must = @("SwiftUI"); minNamed = 6; action = $null }
    # Only the contentScale / filterQuality labels are platform-independent here.
    "Texture"         = @{ must = @("FillBounds", "Crop", "None (nearest)"); minNamed = 8; action = $null }
}

$report = New-Object System.Collections.Generic.List[string]

foreach ($tabName in $tabChecks.Keys) {
    Write-Host "`n-- Tab: $tabName --"
    try {
        Select-Tab $win $tabName
    } catch {
        Assert $false "navigate to tab '$tabName': $_"
        continue
    }

    $names = All-Named $win
    $report.Add("TAB=$tabName count=$($names.Count)")
    Assert ($names.Count -ge $tabChecks[$tabName].minNamed) `
        "$tabName tree has >= $($tabChecks[$tabName].minNamed) named nodes (got $($names.Count))"

    foreach ($must in $tabChecks[$tabName].must) {
        $hit = $false
        foreach ($n in $names) {
            if ($n -eq $must -or $n.Contains($must)) { $hit = $true; break }
        }
        # Also exact find with short timeout (fresh walk)
        if (-not $hit) {
            $hit = $null -ne (Find-ByName $win $must 4)
        }
        Assert $hit "$tabName exposes '$must'"
    }

    switch ($tabChecks[$tabName].action) {
        "a11y" {
            $inc = Find-ByName $win "Increment" 5
            Assert ($null -ne $inc -and (Try-Invoke $inc)) "A11y Invoke Increment"
            Start-Sleep -Milliseconds 700
            $ctrOk = $false
            foreach ($i in 1..30) {
                if ($null -ne (Find-ByName $win "click counter $i" 1)) { $ctrOk = $true; break }
            }
            Assert $ctrOk "A11y counter updated after Increment"

            $cb = Find-ByName $win "Tri-state checkbox" 5
            if ($cb) {
                $before = $null
                try {
                    $before = $cb.GetCurrentPattern(
                        [System.Windows.Automation.TogglePattern]::Pattern).Current.ToggleState
                    $cb.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern).Toggle()
                    Start-Sleep -Milliseconds 700
                    $cb2 = Find-ByName $win "Tri-state checkbox" 3
                    $after = $cb2.GetCurrentPattern(
                        [System.Windows.Automation.TogglePattern]::Pattern).Current.ToggleState
                    Assert ($before -ne $after) "A11y Toggle checkbox $before -> $after"
                } catch {
                    Assert $false "A11y Toggle checkbox: $_"
                }
            } else { Assert $false "A11y checkbox missing for toggle" }

            $vol = Find-ByName $win "Volume" 5
            if ($vol) {
                try {
                    $vol.GetCurrentPattern(
                        [System.Windows.Automation.RangeValuePattern]::Pattern).SetValue(0.55)
                    Start-Sleep -Milliseconds 700
                    $v = (Find-ByName $win "Volume" 3).GetCurrentPattern(
                        [System.Windows.Automation.RangeValuePattern]::Pattern).Current.Value
                    Assert ([math]::Abs($v - 0.55) -lt 0.08) "A11y RangeValue Volume=$v"
                } catch { Assert $false "A11y Volume: $_" }
            }
        }
        "complex" {
            $add = Find-ByName $win "Add item" 5
            Assert ($null -ne $add -and (Try-Invoke $add)) "Complex Invoke Add item"
            Start-Sleep -Milliseconds 800
            Assert ($null -ne (Find-ByNameContains $win "Item #")) "Complex new Item appears"

            $toggle = Find-ByNameContains $win "Toggle done for Buy milk"
            if ($null -eq $toggle) { $toggle = Find-ByName $win "Toggle done for Buy milk" 3 }
            if ($toggle) {
                try {
                    $before = $toggle.GetCurrentPattern(
                        [System.Windows.Automation.TogglePattern]::Pattern).Current.ToggleState
                    $toggle.GetCurrentPattern(
                        [System.Windows.Automation.TogglePattern]::Pattern).Toggle()
                    Start-Sleep -Milliseconds 700
                    $t2 = Find-ByNameContains $win "Toggle done for Buy milk"
                    $after = $t2.GetCurrentPattern(
                        [System.Windows.Automation.TogglePattern]::Pattern).Current.ToggleState
                    Assert ($before -ne $after) "Complex Toggle Buy milk $before -> $after"
                } catch { Assert $false "Complex Toggle: $_" }
            } else { Assert $false "Complex Toggle done for Buy milk missing" }

            $group = Find-ByNameContains $win "settings)"
            Assert ($null -ne $group) "Complex expandable (settings) present"
            if ($group) {
                $nameBefore = $group.Current.Name
                $expanded = Try-ExpandCollapse $group
                if (-not $expanded) {
                    # Fall back to Invoke if ExpandCollapse not advertised
                    $expanded = Try-Invoke $group
                }
                Start-Sleep -Milliseconds 800
                $g2 = Find-ByNameContains $win "settings)"
                $nameAfter = if ($g2) { $g2.Current.Name } else { "" }
                Assert ($expanded -and ($nameBefore -ne $nameAfter -or $nameAfter -match "expanded|collapsed")) `
                    "Complex expand/collapse settings ('$nameBefore' -> '$nameAfter')"
            }

            $start = Find-ByName $win "Start" 5
            Assert ($null -ne $start) "Complex ticker Start"
            if ($start) {
                Assert (Try-Invoke $start) "Complex Invoke Start"
                Start-Sleep -Milliseconds 900
                Assert ($null -ne (Find-ByName $win "Stop" 5)) "Complex Start -> Stop"
            }
        }
    }
}

Write-Host "`n== $failures failure(s) =="
$report | ForEach-Object { Write-Host $_ }
exit $(if ($failures -gt 0) { 1 } else { 0 })
