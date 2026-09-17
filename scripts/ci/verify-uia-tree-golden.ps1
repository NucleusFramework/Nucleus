# Exact expected-vs-measured UIA tree verification for tao-demo.
#
# Method:
#   1. EXPECTED = scripts/ci/a11y-goldens/<fixture>.expected.json
#      (source of truth catalog: name, ControlType, patterns, enabled, focusable,
#       optional toggle/selected/range/value constraints)
#   2. MEASURED = live RawViewWalker dump of the OS accessibility tree
#   3. DIFF     = field-by-field match; exit non-zero on any MISSING / mismatch
#
# Also writes:
#   - measured JSON snapshot (actual tree) next to the fixture when -OutDir set
#   - human-readable diff summary
#
# Usage:
#   pwsh scripts/ci/verify-uia-tree-golden.ps1
#   pwsh scripts/ci/verify-uia-tree-golden.ps1 -Fixture a11y-tab -OutDir $env:TEMP\a11y-diff
#   pwsh scripts/ci/verify-uia-tree-golden.ps1 -DumpOnly -OutDir .\measured
#
# Exit 0 = every expected node matches measured properties exactly.

param(
    [string]$Title = "Tao Backend Demo",
    [string]$Fixture = "a11y-tab",
    [string]$GoldensDir = "",
    [string]$OutDir = "",
    [int]$TimeoutSec = 90,
    [switch]$DumpOnly,
    [switch]$StrictExtra
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

if (-not $GoldensDir) {
    $GoldensDir = Join-Path $PSScriptRoot "a11y-goldens"
}
$expectedPath = Join-Path $GoldensDir "$Fixture.expected.json"
if (-not (Test-Path $expectedPath)) {
    throw "expected fixture not found: $expectedPath"
}

$PatternMap = @{
    ([System.Windows.Automation.InvokePattern]::Pattern.Id)          = "Invoke"
    ([System.Windows.Automation.TogglePattern]::Pattern.Id)          = "Toggle"
    ([System.Windows.Automation.ValuePattern]::Pattern.Id)           = "Value"
    ([System.Windows.Automation.RangeValuePattern]::Pattern.Id)      = "RangeValue"
    ([System.Windows.Automation.SelectionItemPattern]::Pattern.Id)   = "SelectionItem"
    ([System.Windows.Automation.SelectionPattern]::Pattern.Id)       = "Selection"
    ([System.Windows.Automation.ScrollPattern]::Pattern.Id)          = "Scroll"
    ([System.Windows.Automation.ExpandCollapsePattern]::Pattern.Id)  = "ExpandCollapse"
    ([System.Windows.Automation.TextPattern]::Pattern.Id)            = "Text"
    ([System.Windows.Automation.WindowPattern]::Pattern.Id)          = "Window"
}

function Find-WindowExact([string]$title, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $root = [System.Windows.Automation.AutomationElement]::RootElement
        foreach ($w in $root.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)) {
            try { if ($w.Current.Name -eq $title) { return $w } } catch {}
        }
        Start-Sleep 1
    }
    throw "window '$title' not found"
}

function Get-PatternNames($el) {
    $names = New-Object System.Collections.Generic.List[string]
    try {
        foreach ($p in $el.GetSupportedPatterns()) {
            if ($PatternMap.ContainsKey($p.Id)) { [void]$names.Add($PatternMap[$p.Id]) }
        }
    } catch {}
    return @($names | Sort-Object -Unique)
}

function Measure-Node($el) {
    $o = [ordered]@{
        name              = $null
        controlType       = $null
        enabled           = $null
        keyboardFocusable = $null
        offscreen         = $null
        hasKeyboardFocus  = $null
        patterns          = @()
        toggleState       = $null
        selected          = $null
        value             = $null
        rangeValue        = $null
        rangeMin          = $null
        rangeMax          = $null
        expandState       = $null
        bounds            = $null
        boundsValid       = $false
    }
    try {
        $o.name = $el.Current.Name
        $o.controlType = ($el.Current.ControlType.ProgrammaticName -replace '^ControlType\.', '')
        $o.enabled = [bool]$el.Current.IsEnabled
        $o.keyboardFocusable = [bool]$el.Current.IsKeyboardFocusable
        $o.offscreen = [bool]$el.Current.IsOffscreen
        $o.hasKeyboardFocus = [bool]$el.Current.HasKeyboardFocus
        $r = $el.Current.BoundingRectangle
        $o.bounds = [ordered]@{ x = [double]$r.X; y = [double]$r.Y; w = [double]$r.Width; h = [double]$r.Height }
        $o.boundsValid = ($r.Width -gt 0 -and $r.Height -gt 0)
        $pats = Get-PatternNames $el
        $o.patterns = @($pats)
        if ($pats -contains "Toggle") {
            try {
                $o.toggleState = $el.GetCurrentPattern(
                    [System.Windows.Automation.TogglePattern]::Pattern).Current.ToggleState.ToString()
            } catch {}
        }
        if ($pats -contains "SelectionItem") {
            try {
                $o.selected = [bool]$el.GetCurrentPattern(
                    [System.Windows.Automation.SelectionItemPattern]::Pattern).Current.IsSelected
            } catch {}
        }
        if ($pats -contains "Value") {
            try {
                $o.value = $el.GetCurrentPattern(
                    [System.Windows.Automation.ValuePattern]::Pattern).Current.Value
            } catch {}
        }
        if ($pats -contains "RangeValue") {
            try {
                $rv = $el.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern)
                $o.rangeValue = [double]$rv.Current.Value
                $o.rangeMin = [double]$rv.Current.Minimum
                $o.rangeMax = [double]$rv.Current.Maximum
            } catch {}
        }
        if ($pats -contains "ExpandCollapse") {
            try {
                $o.expandState = $el.GetCurrentPattern(
                    [System.Windows.Automation.ExpandCollapsePattern]::Pattern).Current.ExpandCollapseState.ToString()
            } catch {}
        }
    } catch {}
    return $o
}

function Collect-Measured($window) {
    $list = New-Object System.Collections.ArrayList
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    function Walk($el, [int]$depth) {
        if ($null -eq $el -or $depth -gt 16 -or $list.Count -gt 4000) { return }
        $m = Measure-Node $el
        $m["depth"] = $depth
        [void]$list.Add([pscustomobject]$m)
        try { $child = $walker.GetFirstChild($el) } catch { return }
        while ($null -ne $child) {
            Walk $child ($depth + 1)
            try { $child = $walker.GetNextSibling($child) } catch { break }
        }
    }
    Walk $window 0
    return ,$list.ToArray()
}

function Name-Matches($measuredName, $spec) {
    $match = if ($spec.nameMatch) { $spec.nameMatch } else { "exact" }
    $expected = [string]$spec.name
    $actual = if ($null -eq $measuredName) { "" } else { [string]$measuredName }
    switch ($match) {
        "exact"  { return ($actual -eq $expected) }
        "regex"  { return ($actual -match $expected) }
        "prefix" { return ($actual.StartsWith($expected)) }
        "contains" { return ($actual.Contains($expected)) }
        "any"    { return $true }
        default  { return ($actual -eq $expected) }
    }
}

function Value-Matches($measuredValue, $spec) {
    if (-not $spec.PSObject.Properties["value"]) { return $true }
    $match = if ($spec.valueMatch) { $spec.valueMatch } else { "exact" }
    $expected = [string]$spec.value
    $actual = if ($null -eq $measuredValue) { "" } else { [string]$measuredValue }
    switch ($match) {
        "exact" { return ($actual -eq $expected) }
        "regex" { return ($actual -match $expected) }
        "any"   { return $true }
        default { return ($actual -eq $expected) }
    }
}

function Find-MeasuredMatch($measured, $spec) {
    $candidates = @()
    foreach ($m in $measured) {
        if ($spec.controlType -and $m.controlType -ne $spec.controlType) { continue }
        if (-not (Name-Matches $m.name $spec)) { continue }
        if (-not (Value-Matches $m.value $spec)) { continue }
        $candidates += $m
    }
    if ($candidates.Count -eq 0) { return $null }
    # Prefer on-screen valid bounds when multiple matches
    $best = $candidates | Where-Object { $_.boundsValid } | Select-Object -First 1
    if ($best) { return $best }
    return $candidates[0]
}

function Select-Tab($window, [string]$tabName) {
    $measured = Collect-Measured $window
    $tab = $null
    foreach ($m in $measured) {
        if ($m.name -eq $tabName -and $m.controlType -eq "TabItem") {
            # re-find live element by name
            break
        }
    }
    # Live element lookup
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    function FindEl($el, $name, $depth) {
        if ($null -eq $el -or $depth -gt 14) { return $null }
        try { if ($el.Current.Name -eq $name) { return $el } } catch {}
        try { $c = $walker.GetFirstChild($el) } catch { return $null }
        while ($null -ne $c) {
            $r = FindEl $c $name ($depth + 1)
            if ($r) { return $r }
            try { $c = $walker.GetNextSibling($c) } catch { break }
        }
        return $null
    }
    $tabEl = FindEl $window $tabName 0
    if (-not $tabEl) { throw "tab '$tabName' not found" }
    try {
        $tabEl.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
    } catch {
        try { $tabEl.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() } catch {
            throw "cannot select tab '$tabName'"
        }
    }
    Start-Sleep -Milliseconds 900
}

# --- load expected ---
$expected = Get-Content -Raw -Path $expectedPath | ConvertFrom-Json
Write-Host "== UIA tree golden verification =="
Write-Host "fixture: $expectedPath"
Write-Host "tab: $($expected.tab)  window: $($expected.windowTitle)"

$win = Find-WindowExact $(if ($expected.windowTitle) { $expected.windowTitle } else { $Title }) $TimeoutSec
Write-Host "window hwnd=$($win.Current.NativeWindowHandle)"

if ($expected.tab) {
    Write-Host "selecting tab '$($expected.tab)'..."
    Select-Tab $win $expected.tab
}

$measured = Collect-Measured $win
Write-Host ("measured nodes: {0}" -f $measured.Count)

if ($OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $measuredPath = Join-Path $OutDir "$Fixture.measured.json"
    $measured | ConvertTo-Json -Depth 8 | Set-Content -Path $measuredPath -Encoding UTF8
    Write-Host "wrote measured snapshot: $measuredPath"
}

if ($DumpOnly) {
    Write-Host "DumpOnly: skip diff"
    exit 0
}

$failures = New-Object System.Collections.Generic.List[string]
$passes = 0

function Fail([string]$msg) {
    [void]$script:failures.Add($msg)
    Write-Host "  [FAIL] $msg"
}
function Pass([string]$msg) {
    $script:passes++
    Write-Host "  [PASS] $msg"
}

# --- tabs ---
Write-Host "`n-- Tab bar (expected vs measured) --"
if ($expected.tabs) {
    foreach ($t in $expected.tabs) {
        $spec = $t
        # coerce to object with nameMatch
        $spec | Add-Member -NotePropertyName nameMatch -NotePropertyValue "exact" -Force
        $m = Find-MeasuredMatch $measured $spec
        if (-not $m) {
            Fail "TAB missing name='$($t.name)' type=$($t.controlType)"
            continue
        }
        $ok = $true
        if ($t.keyboardFocusable -ne $null -and [bool]$m.keyboardFocusable -ne [bool]$t.keyboardFocusable) {
            Fail "TAB '$($t.name)' keyboardFocusable expected=$($t.keyboardFocusable) actual=$($m.keyboardFocusable)"
            $ok = $false
        }
        if ($t.patternsAny) {
            $hit = $false
            foreach ($p in $t.patternsAny) { if ($m.patterns -contains $p) { $hit = $true; break } }
            if (-not $hit) {
                Fail ("TAB '{0}' patternsAny=[{1}] actual=[{2}]" -f $t.name, ($t.patternsAny -join ','), ($m.patterns -join ','))
                $ok = $false
            }
        }
        if ($t.PSObject.Properties["selected"] -and $null -ne $t.selected) {
            if ($null -eq $m.selected) {
                # Selected tab may use SelectionItem only when selected in some mappings
                if ([bool]$t.selected) {
                    # soft: if patterns have SelectionItem, selected must be readable
                    if ($m.patterns -contains "SelectionItem" -and -not $m.selected) {
                        Fail "TAB '$($t.name)' selected expected=True actual=False/null"
                        $ok = $false
                    }
                }
            } elseif ([bool]$m.selected -ne [bool]$t.selected) {
                Fail "TAB '$($t.name)' selected expected=$($t.selected) actual=$($m.selected)"
                $ok = $false
            }
        }
        if ($ok) { Pass ("TAB '{0}' type={1} patterns=[{2}] selected={3}" -f $t.name, $m.controlType, ($m.patterns -join ','), $m.selected) }
    }
}

# --- content nodes ---
Write-Host "`n-- Content nodes (expected vs measured) --"
$matchedNames = New-Object System.Collections.Generic.HashSet[string]
foreach ($spec in $expected.nodes) {
    $m = Find-MeasuredMatch $measured $spec
    $label = if ($spec.id) { $spec.id } else { $spec.name }
    if (-not $m) {
        Fail ("MISSING id={0} name={1} type={2}" -f $label, $spec.name, $spec.controlType)
        continue
    }
    if ($m.name) { [void]$matchedNames.Add([string]$m.name) }

    $nodeFails = New-Object System.Collections.Generic.List[string]

    if ($spec.controlType -and $m.controlType -ne $spec.controlType) {
        [void]$nodeFails.Add("type expected=$($spec.controlType) actual=$($m.controlType)")
    }
    if ($spec.PSObject.Properties["enabled"] -and $null -ne $spec.enabled) {
        if ([bool]$m.enabled -ne [bool]$spec.enabled) {
            [void]$nodeFails.Add("enabled expected=$($spec.enabled) actual=$($m.enabled)")
        }
    }
    if ($spec.PSObject.Properties["keyboardFocusable"] -and $null -ne $spec.keyboardFocusable) {
        if ([bool]$m.keyboardFocusable -ne [bool]$spec.keyboardFocusable) {
            [void]$nodeFails.Add("focusable expected=$($spec.keyboardFocusable) actual=$($m.keyboardFocusable)")
        }
    }
    if ($spec.patternsAll) {
        foreach ($p in $spec.patternsAll) {
            if ($m.patterns -notcontains $p) {
                [void]$nodeFails.Add("missing pattern $p (actual=[$($m.patterns -join ',')])")
            }
        }
    }
    if ($spec.patternsNone) {
        foreach ($p in $spec.patternsNone) {
            if ($m.patterns -contains $p) {
                [void]$nodeFails.Add("forbidden pattern $p present")
            }
        }
    }
    if ($spec.PSObject.Properties["rangeMin"] -and $null -ne $spec.rangeMin) {
        if ($null -eq $m.rangeMin -or [math]::Abs([double]$m.rangeMin - [double]$spec.rangeMin) -gt 0.001) {
            [void]$nodeFails.Add("rangeMin expected=$($spec.rangeMin) actual=$($m.rangeMin)")
        }
    }
    if ($spec.PSObject.Properties["rangeMax"] -and $null -ne $spec.rangeMax) {
        if ($null -eq $m.rangeMax -or [math]::Abs([double]$m.rangeMax - [double]$spec.rangeMax) -gt 0.001) {
            [void]$nodeFails.Add("rangeMax expected=$($spec.rangeMax) actual=$($m.rangeMax)")
        }
    }
    if ($spec.requireValidBounds -eq $true) {
        if (-not $m.boundsValid) {
            [void]$nodeFails.Add(("bounds invalid w={0} h={1}" -f $m.bounds.w, $m.bounds.h))
        }
    }
    if (-not (Value-Matches $m.value $spec)) {
        [void]$nodeFails.Add("value expected~=$($spec.value) actual='$($m.value)'")
    }

    if ($nodeFails.Count -eq 0) {
        Pass ("{0}: name='{1}' type={2} en={3} focusable={4} pat=[{5}] boundsOK={6}" -f `
            $label, $m.name, $m.controlType, $m.enabled, $m.keyboardFocusable, ($m.patterns -join ','), $m.boundsValid)
    } else {
        foreach ($f in $nodeFails) {
            Fail ("{0}: {1}" -f $label, $f)
        }
    }
}

# Optional: flag interactive named nodes not covered by golden (detect drift)
if ($StrictExtra) {
    Write-Host "`n-- Strict extra (unlisted interactive) --"
    $interactiveTypes = @("Button", "CheckBox", "RadioButton", "Edit", "Slider", "TabItem", "ComboBox", "Hyperlink")
    # OS / shell chrome (EN + FR locale) and demo title-bar utilities ? not app content.
    $chromeNames = @(
        "Minimize", "Maximize", "Close", "Restore", "Clear",
        "R?duire", "Agrandir", "Fermer", "Restaurer",
        "R" + [char]0x00E9 + "duire"  # R?duire
    )
    foreach ($m in $measured) {
        if (-not $m.name) { continue }
        if ($interactiveTypes -notcontains $m.controlType) { continue }
        if ($m.name -in $chromeNames) { continue }
        # Localized min/max/close: match by known patterns when OS language != EN
        if ($m.controlType -eq "Button" -and $m.patterns -contains "Invoke" -and $m.depth -le 3) {
            if ($m.name -match '^(Minimize|Maximize|Close|Restore|R.duire|Agrandir|Fermer|Restaurer)$') { continue }
        }
        if ($m.name -eq "Tao Backend Demo") { continue }
        # Reorder arrows (up/down) often surface as single-glyph names without a stable string.
        if ($m.name.Length -le 2 -and $m.controlType -eq "Button") { continue }
        # Expand/collapse groups: covered by contains-match on settings/advanced/always shown
        if ($m.patterns -contains "ExpandCollapse") { continue }
        $covered = $false
        foreach ($spec in $expected.nodes) {
            if ($spec.controlType -and $m.controlType -ne $spec.controlType) { continue }
            if (Name-Matches $m.name $spec) { $covered = $true; break }
        }
        foreach ($t in $expected.tabs) {
            if ($m.name -eq $t.name) { $covered = $true; break }
        }
        if (-not $covered) {
            Fail ("EXTRA unlisted interactive name='{0}' type={1} pat=[{2}]" -f $m.name, $m.controlType, ($m.patterns -join ','))
        }
    }
}

# Summary table: expected id -> actual signature
if ($OutDir) {
    $diffPath = Join-Path $OutDir "$Fixture.diff.txt"
    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add("EXPECTED vs MEASURED ? $Fixture")
    [void]$lines.Add("expected: $expectedPath")
    [void]$lines.Add("measured nodes: $($measured.Count)")
    [void]$lines.Add("passes: $passes  failures: $($failures.Count)")
    [void]$lines.Add("")
    foreach ($f in $failures) { [void]$lines.Add("FAIL: $f") }
    $lines | Set-Content -Path $diffPath -Encoding UTF8
    Write-Host "wrote diff: $diffPath"
}

Write-Host ("`n== {0} failure(s), {1} pass(es) ==" -f $failures.Count, $passes)
if ($failures.Count -gt 0) {
    Write-Host "`nFailures:"
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
