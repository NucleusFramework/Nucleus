# Dumps the UI Automation tree of a window, matched by process name or window
# title substring. Used to validate the Tao backend's UIA provider without
# having to launch Narrator/NVDA.
#
# Usage:
#   pwsh scripts/dump-uia-tree.ps1 -Process java
#   pwsh scripts/dump-uia-tree.ps1 -Title "sample-tao"
#   pwsh scripts/dump-uia-tree.ps1 -Title "sample" -MaxDepth 4 -Json
#
# Output: indented tree (default) or JSON (-Json), one line per element with
# ControlType, Name, BoundingRectangle and supported patterns. Lets us verify
# the snapshot push produces sane UIA shapes (Button-with-Invoke, Edit-with-Value,
# etc.) without an audible screen reader.

[CmdletBinding(DefaultParameterSetName = 'ByTitle')]
param(
    [Parameter(ParameterSetName = 'ByTitle')]
    [string]$Title,

    [Parameter(ParameterSetName = 'ByProcess')]
    [string]$Process,

    [Parameter(ParameterSetName = 'ByHwnd')]
    [int]$Hwnd,

    [int]$MaxDepth = 8,
    [switch]$Json,
    [switch]$Patterns,
    [switch]$Watch,
    [int]$WatchIntervalMs = 1000
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

# Map of all interesting pattern Ids -> short name. Documented in
# https://learn.microsoft.com/en-us/dotnet/api/system.windows.automation.automationelement.getsupportedpatterns
$PatternMap = @{
    [System.Windows.Automation.InvokePattern]::Pattern.Id        = 'Invoke'
    [System.Windows.Automation.TogglePattern]::Pattern.Id        = 'Toggle'
    [System.Windows.Automation.ValuePattern]::Pattern.Id         = 'Value'
    [System.Windows.Automation.RangeValuePattern]::Pattern.Id    = 'RangeValue'
    [System.Windows.Automation.SelectionItemPattern]::Pattern.Id = 'SelectionItem'
    [System.Windows.Automation.SelectionPattern]::Pattern.Id     = 'Selection'
    [System.Windows.Automation.ScrollPattern]::Pattern.Id        = 'Scroll'
    [System.Windows.Automation.ScrollItemPattern]::Pattern.Id    = 'ScrollItem'
    [System.Windows.Automation.TextPattern]::Pattern.Id          = 'Text'
    [System.Windows.Automation.GridPattern]::Pattern.Id          = 'Grid'
    [System.Windows.Automation.GridItemPattern]::Pattern.Id      = 'GridItem'
    [System.Windows.Automation.TablePattern]::Pattern.Id         = 'Table'
    [System.Windows.Automation.TableItemPattern]::Pattern.Id     = 'TableItem'
    [System.Windows.Automation.ExpandCollapsePattern]::Pattern.Id = 'ExpandCollapse'
    [System.Windows.Automation.WindowPattern]::Pattern.Id        = 'Window'
    [System.Windows.Automation.TransformPattern]::Pattern.Id     = 'Transform'
}

function Resolve-RootElement {
    if ($Hwnd) {
        return [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$Hwnd)
    }
    if ($Process) {
        $procs = Get-Process -Name $Process -ErrorAction SilentlyContinue |
                 Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero }
        if (-not $procs) {
            throw "No process named '$Process' with a visible main window."
        }
        $p = $procs | Select-Object -First 1
        Write-Host "Found process $($p.Id) ($($p.ProcessName)) hwnd=0x$($p.MainWindowHandle.ToInt64().ToString('X'))" -ForegroundColor Cyan
        return [System.Windows.Automation.AutomationElement]::FromHandle($p.MainWindowHandle)
    }
    if ($Title) {
        $cond = New-Object System.Windows.Automation.PropertyCondition (
            [System.Windows.Automation.AutomationElement]::NameProperty,
            $Title,
            [System.Windows.Automation.PropertyConditionFlags]::IgnoreCase)
        # NameProperty match is exact; emulate substring by walking top-level windows.
        $root = [System.Windows.Automation.AutomationElement]::RootElement
        $tlw = $root.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($w in $tlw) {
            try {
                $n = $w.Current.Name
                if ($n -and $n.ToLower().Contains($Title.ToLower())) {
                    Write-Host "Matched window: '$n' hwnd=0x$($w.Current.NativeWindowHandle.ToString('X'))" -ForegroundColor Cyan
                    return $w
                }
            } catch {}
        }
        throw "No top-level window matched title substring '$Title'."
    }
    throw "Specify -Title, -Process or -Hwnd."
}

function Get-PatternList($element) {
    try {
        $supported = $element.GetSupportedPatterns()
    } catch {
        return @()
    }
    $names = @()
    foreach ($p in $supported) {
        if ($PatternMap.ContainsKey($p.Id)) {
            $names += $PatternMap[$p.Id]
        } else {
            $names += "Pattern#$($p.Id)"
        }
    }
    return $names
}

function Get-ExtraPatternInfo($element, $patternNames) {
    $extras = @{}
    if ($patternNames -contains 'Toggle') {
        try {
            $tp = $element.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
            $extras['toggleState'] = $tp.Current.ToggleState.ToString()
        } catch {}
    }
    if ($patternNames -contains 'Value') {
        try {
            $vp = $element.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
            $extras['value'] = $vp.Current.Value
            $extras['valueIsReadOnly'] = $vp.Current.IsReadOnly
        } catch {}
    }
    if ($patternNames -contains 'RangeValue') {
        try {
            $rp = $element.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern)
            $extras['range'] = "$($rp.Current.Minimum)..$($rp.Current.Maximum) cur=$($rp.Current.Value)"
        } catch {}
    }
    if ($patternNames -contains 'SelectionItem') {
        try {
            $sp = $element.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
            $extras['isSelected'] = $sp.Current.IsSelected
        } catch {}
    }
    return $extras
}

function Format-Element($element) {
    $c = $element.Current
    $rect = if ($c.BoundingRectangle.IsEmpty) { 'empty' }
            else { "[$([int]$c.BoundingRectangle.X),$([int]$c.BoundingRectangle.Y) $([int]$c.BoundingRectangle.Width)x$([int]$c.BoundingRectangle.Height)]" }
    $patternNames = Get-PatternList $element
    $extras = Get-ExtraPatternInfo $element $patternNames
    return [PSCustomObject]@{
        controlType = $c.ControlType.LocalizedControlType
        name        = $c.Name
        autoId      = $c.AutomationId
        runtimeId   = ($element.GetRuntimeId() -join '.')
        rect        = $rect
        enabled     = $c.IsEnabled
        focusable   = $c.IsKeyboardFocusable
        focused     = $c.HasKeyboardFocus
        patterns    = $patternNames
        extras      = $extras
    }
}

function Walk-Tree($element, $depth, $maxDepth, [System.Collections.ArrayList]$flat) {
    if ($depth -gt $maxDepth) { return }
    $info = Format-Element $element
    $info | Add-Member -NotePropertyName depth -NotePropertyValue $depth -Force
    $null = $flat.Add($info)
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    $child = $walker.GetFirstChild($element)
    while ($child) {
        Walk-Tree $child ($depth + 1) $maxDepth $flat
        $child = $walker.GetNextSibling($child)
    }
}

function Print-Tree($flat) {
    foreach ($e in $flat) {
        $indent = '  ' * $e.depth
        $patternStr = if ($e.patterns.Count -gt 0) { '{' + ($e.patterns -join ',') + '}' } else { '' }
        $extrasStr = ''
        if ($e.extras.Count -gt 0) {
            $kv = $e.extras.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
            $extrasStr = ' (' + ($kv -join ', ') + ')'
        }
        $name = if ($e.name) { '"' + $e.name + '"' } else { '<unnamed>' }
        $flags = @()
        if (-not $e.enabled)  { $flags += 'disabled' }
        if ($e.focused)       { $flags += 'focused' }
        if ($e.focusable -and -not $e.focused) { $flags += 'focusable' }
        $flagStr = if ($flags.Count -gt 0) { ' [' + ($flags -join ',') + ']' } else { '' }
        Write-Host "$indent$($e.controlType): $name $($e.rect)$flagStr $patternStr$extrasStr"
    }
}

function Dump-Once {
    $root = Resolve-RootElement
    if (-not $root) { throw 'Root element resolved to null.' }
    $flat = New-Object System.Collections.ArrayList
    Walk-Tree $root 0 $MaxDepth $flat
    if ($Json) {
        $flat | ConvertTo-Json -Depth 8
    } else {
        Print-Tree $flat
        Write-Host ''
        Write-Host "Total elements: $($flat.Count)" -ForegroundColor Yellow
    }
}

if ($Watch) {
    while ($true) {
        Clear-Host
        Write-Host "[$([DateTime]::Now.ToString('HH:mm:ss'))] dump-uia-tree (Ctrl+C to stop)" -ForegroundColor DarkCyan
        try { Dump-Once } catch { Write-Host $_.Exception.Message -ForegroundColor Red }
        Start-Sleep -Milliseconds $WatchIntervalMs
    }
} else {
    Dump-Once
}
