# Listens for UIA automation events on a window — used to verify that the
# provider is firing PropertyChanged / FocusChanged / NotificationEvent on
# state transitions. Useful regression check for the events implementation.
#
# Usage:
#   pwsh scripts/uia-listen-events.ps1 -Title "Tao Backend Demo" -DurationSec 30

param(
    [Parameter(Mandatory)] [string]$Title,
    [int]$DurationSec = 30
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
Write-Host "Listening on: $($target.Current.Name) (Ctrl+C to stop or wait $DurationSec s)" -ForegroundColor Cyan

$propHandler = [System.Windows.Automation.AutomationPropertyChangedEventHandler]{
    param($sender, $e)
    $el = $sender -as [System.Windows.Automation.AutomationElement]
    $name = if ($el) { $el.Current.Name } else { '?' }
    Write-Host ("[Prop] {0}.{1}: {2} -> {3}" -f $name, $e.Property.ProgrammaticName, $e.OldValue, $e.NewValue) -ForegroundColor Yellow
}
$focusHandler = [System.Windows.Automation.AutomationFocusChangedEventHandler]{
    param($sender, $e)
    $el = $sender -as [System.Windows.Automation.AutomationElement]
    $name = if ($el) { $el.Current.Name } else { '?' }
    Write-Host ("[Focus] -> {0}" -f $name) -ForegroundColor Magenta
}
$genericHandler = [System.Windows.Automation.AutomationEventHandler]{
    param($sender, $e)
    $el = $sender -as [System.Windows.Automation.AutomationElement]
    $name = if ($el) { $el.Current.Name } else { '?' }
    Write-Host ("[Event] {0} on {1}" -f $e.EventId.ProgrammaticName, $name) -ForegroundColor Green
}

# Subscribe to property-changed events for the patterns we care about.
$props = @(
    [System.Windows.Automation.AutomationElement]::NameProperty,
    [System.Windows.Automation.ValuePattern]::ValueProperty,
    [System.Windows.Automation.TogglePattern]::ToggleStateProperty,
    [System.Windows.Automation.RangeValuePattern]::ValueProperty,
    [System.Windows.Automation.SelectionItemPattern]::IsSelectedProperty
)
[System.Windows.Automation.Automation]::AddAutomationPropertyChangedEventHandler(
    $target, [System.Windows.Automation.TreeScope]::Subtree, $propHandler, $props)

# Focus is global (not per-element).
[System.Windows.Automation.Automation]::AddAutomationFocusChangedEventHandler($focusHandler)

# Generic events (StructureChanged is on the element via dedicated handler;
# subscribe to common ones).
foreach ($evt in @(
    [System.Windows.Automation.AutomationElement]::AsyncContentLoadedEvent,
    [System.Windows.Automation.InvokePattern]::InvokedEvent
)) {
    try {
        [System.Windows.Automation.Automation]::AddAutomationEventHandler(
            $evt, $target, [System.Windows.Automation.TreeScope]::Subtree, $genericHandler)
    } catch {}
}

try {
    Start-Sleep -Seconds $DurationSec
} finally {
    [System.Windows.Automation.Automation]::RemoveAllEventHandlers()
    Write-Host "Stopped listener." -ForegroundColor Cyan
}
