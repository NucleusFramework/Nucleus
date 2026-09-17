# CI probe: verifies AccessKit Windows UIA *event delivery* end-to-end.
#
# Background (uia-problem.txt):
#   Tree walk + patterns work. Legacy System.Windows.Automation
#   AutomationPropertyChangedEventHandler often gets ZERO events from
#   ServerSideProvider fragment trees. The modern COM UIA client
#   (Interop.UIAutomationClient / CUIAutomation - same stack Narrator uses)
#   DOES receive PropertyChanged. This probe gates on the COM client.
#
# Prerequisites:
#   - tao-demo running with window title "Tao Backend Demo" (NUCLEUS_DEMO_TAB=A11y)
#   - scripts/uia-listener built (dotnet build -c Release)
#
# Exit 0 = at least one ToggleState (30086) or RangeValue (30047) or Name (30005)
# PropertyChanged event observed while driving A11y-tab controls via RawView.

param(
    [string]$Title = "Tao Backend Demo",
    [int]$TimeoutSec = 90,
    [string]$ListenerPath = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

function Find-Window([string]$titleExact, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $root = [System.Windows.Automation.AutomationElement]::RootElement
        $windows = $root.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($w in $windows) {
            try {
                if ($w.Current.Name -eq $titleExact) { return $w }
            } catch {}
        }
        Start-Sleep -Seconds 1
    }
    throw "window '$titleExact' not found within ${timeoutSec}s"
}

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

function Find-ByName($window, [string]$name, [int]$timeoutSec = 20) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $list = New-Object System.Collections.ArrayList
        Collect-Raw $window 0 $list
        foreach ($el in $list) {
            try { if ($el.Current.Name -eq $name) { return $el } } catch {}
        }
        Start-Sleep -Milliseconds 300
    }
    return $null
}

if (-not $ListenerPath) {
    $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    if (-not (Test-Path (Join-Path $repoRoot "scripts/uia-listener"))) {
        $repoRoot = Split-Path $PSScriptRoot -Parent
    }
    $ListenerPath = Join-Path $repoRoot "scripts/uia-listener/bin/Release/net8.0-windows/UiaListener.exe"
}

if (-not (Test-Path $ListenerPath)) {
    $proj = Join-Path (Split-Path (Split-Path $ListenerPath -Parent) -Parent) ".." 
    $csproj = Join-Path (Split-Path $PSScriptRoot -Parent) "uia-listener/UiaListener.csproj"
    if (-not (Test-Path $csproj)) {
        $csproj = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) "scripts/uia-listener/UiaListener.csproj"
    }
    Write-Host "Building UiaListener from $csproj"
    dotnet build $csproj -c Release --nologo | Out-Host
}

if (-not (Test-Path $ListenerPath)) {
    throw "UiaListener.exe not found at $ListenerPath - build scripts/uia-listener first"
}

Write-Host "-- UIA event verification (COM client) --"
$win = Find-Window $Title $TimeoutSec
Write-Host "window: '$($win.Current.Name)'"

# Ensure A11y content is up (navigate via UIA if needed).
if ($null -eq (Find-ByName $win "Increment" 8)) {
    Write-Host "navigating to A11y tab..."
    $tab = Find-ByName $win "A11y" 10
    if ($null -ne $tab) {
        try {
            $tab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
        } catch {
            try { $tab.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() } catch {}
        }
        Start-Sleep -Seconds 1
    }
}
if ($null -eq (Find-ByName $win "Increment" 20)) {
    throw "A11y tab content not found (Increment missing)"
}

$outFile = [System.IO.Path]::GetTempFileName()
$errFile = [System.IO.Path]::GetTempFileName()
# UiaListener args: title substring, wait ms. Quote title for spaces.
$listenerProc = Start-Process -FilePath $ListenerPath `
    -ArgumentList @("`"$Title`"", "25000") `
    -NoNewWindow -PassThru `
    -RedirectStandardOutput $outFile `
    -RedirectStandardError $errFile

Start-Sleep -Seconds 2

# Drive controls via RawView (same as verify-uia.ps1).
$cb = Find-ByName $win "Tri-state checkbox"
if ($null -eq $cb) { throw "Tri-state checkbox missing" }
$cb.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern).Toggle()
Start-Sleep -Seconds 1

$inc = Find-ByName $win "Increment"
if ($null -eq $inc) { throw "Increment missing" }
$inc.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
Start-Sleep -Seconds 1

$vol = Find-ByName $win "Volume"
if ($null -eq $vol) { throw "Volume missing" }
$vol.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern).SetValue(0.65)
Start-Sleep -Seconds 2

# Wait for listener window.
try { Wait-Process -Id $listenerProc.Id -Timeout 30 } catch {}
if (-not $listenerProc.HasExited) {
    Stop-Process -Id $listenerProc.Id -Force -ErrorAction SilentlyContinue
}

$transcript = Get-Content $outFile -ErrorAction SilentlyContinue
$transcript | ForEach-Object { Write-Host $_ }

$propLines = @($transcript | Where-Object { $_ -match '^\[Prop\]' })
$toggleLines = @($propLines | Where-Object { $_ -match 'propId=30086' })
$rangeLines = @($propLines | Where-Object { $_ -match 'propId=30047' })
$nameLines = @($propLines | Where-Object { $_ -match 'propId=30005' })

Write-Host "prop events total: $($propLines.Count) (ToggleState=$($toggleLines.Count) RangeValue=$($rangeLines.Count) Name=$($nameLines.Count))"

$failures = 0
function Assert($cond, [string]$what) {
    if ($cond) { Write-Host "  [PASS] $what" }
    else { Write-Host "  [FAIL] $what"; $script:failures++ }
}

Assert ($propLines.Count -ge 1) "COM client received >=1 PropertyChanged"
Assert ($toggleLines.Count -ge 1 -or $rangeLines.Count -ge 1 -or $nameLines.Count -ge 1) `
    "PropertyChanged includes ToggleState(30086) and/or RangeValue(30047) and/or Name(30005)"

# Tree still reflects actions (patterns round-trip). Counter value depends on
# prior probes in the same session - accept any "click counter N" name.
$ctr = $null
foreach ($n in 1..20) {
    $ctr = Find-ByName $win "click counter $n" 1
    if ($null -ne $ctr) { break }
}
Assert ($null -ne $ctr) "tree poll: click counter N present after Invoke"

Write-Host "-- $failures failure(s) --"
Remove-Item $outFile, $errFile -Force -ErrorAction SilentlyContinue
exit $(if ($failures -gt 0) { 1 } else { 0 })
