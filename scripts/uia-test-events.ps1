# All-in-one event test: subscribe → trigger action → wait → check captured.
# Avoids subprocess timing issues with the standalone listener.

param(
    [Parameter(Mandatory)] [string]$Title
)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$tlw = $root.FindAll(
    [System.Windows.Automation.TreeScope]::Children,
    [System.Windows.Automation.Condition]::TrueCondition)
$target = $null
foreach ($w in $tlw) {
    try {
        $n = $w.Current.Name
        if ($n -and $n.ToLower().Contains($Title.ToLower())) { $target = $w; break }
    } catch {}
}
if (-not $target) { throw "Window '$Title' not found." }
Write-Host "Target: $($target.Current.Name)" -ForegroundColor Cyan

$captured = New-Object System.Collections.ArrayList

$propHandler = [System.Windows.Automation.AutomationPropertyChangedEventHandler]{
    param($s, $e)
    $sync = [System.Threading.Monitor]::Enter($captured)
    try {
        $entry = "[Prop] $($e.Property.ProgrammaticName): $($e.OldValue) -> $($e.NewValue)"
        [void]$captured.Add($entry)
    } finally { [System.Threading.Monitor]::Exit($captured) }
}
$focusHandler = [System.Windows.Automation.AutomationFocusChangedEventHandler]{
    param($s, $e)
    $el = $s -as [System.Windows.Automation.AutomationElement]
    $name = if ($el) { $el.Current.Name } else { '?' }
    $sync = [System.Threading.Monitor]::Enter($captured)
    try {
        [void]$captured.Add("[Focus] -> $name")
    } finally { [System.Threading.Monitor]::Exit($captured) }
}

$props = @(
    [System.Windows.Automation.AutomationElement]::NameProperty,
    [System.Windows.Automation.ValuePattern]::ValueProperty,
    [System.Windows.Automation.TogglePattern]::ToggleStateProperty,
    [System.Windows.Automation.RangeValuePattern]::ValueProperty,
    [System.Windows.Automation.SelectionItemPattern]::IsSelectedProperty
)
[System.Windows.Automation.Automation]::AddAutomationPropertyChangedEventHandler(
    $target, [System.Windows.Automation.TreeScope]::Subtree, $propHandler, $props)
[System.Windows.Automation.Automation]::AddAutomationFocusChangedEventHandler($focusHandler)

Start-Sleep -Milliseconds 500
Write-Host "Subscribed. Triggering actions in 1s..." -ForegroundColor Yellow
Start-Sleep -Seconds 1

# Trigger actions inline.
function FindByName($name) {
    $cond = New-Object System.Windows.Automation.PropertyCondition (
        [System.Windows.Automation.AutomationElement]::NameProperty, $name)
    return $target.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
}

# 1) Toggle the checkbox
Write-Host "Action: Toggle 'Tri-state checkbox'" -ForegroundColor Cyan
$el = FindByName 'Tri-state checkbox'
if ($el) {
    $p = $el.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
    $p.Toggle()
}
Start-Sleep -Seconds 1

# 2) Click Increment
Write-Host "Action: Invoke 'Increment'" -ForegroundColor Cyan
$el = FindByName 'Increment'
if ($el) {
    $p = $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
    $p.Invoke()
}
Start-Sleep -Seconds 1

# 3) Set Volume range
Write-Host "Action: SetValue 'Volume' = 0.7" -ForegroundColor Cyan
$el = FindByName 'Volume'
if ($el) {
    $p = $el.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern)
    $p.SetValue(0.7)
}

Start-Sleep -Seconds 5  # Give Compose recomposition time to round-trip

[System.Windows.Automation.Automation]::RemoveAllEventHandlers()
Write-Host ""
Write-Host "=== Captured events ($($captured.Count)) ===" -ForegroundColor Green
foreach ($entry in $captured) { Write-Host $entry }
