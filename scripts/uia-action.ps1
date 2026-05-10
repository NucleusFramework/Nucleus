# Multi-pattern action driver: invoke / toggle / set-value / select on a named
# element. Used for round-trip validation of the UIA provider.
#
# Usage:
#   pwsh scripts/uia-action.ps1 -Title "Tao" -Name "Tri-state checkbox" -Action toggle
#   pwsh scripts/uia-action.ps1 -Title "Tao" -Name "Volume" -Action setvalue -Value 0.7
#   pwsh scripts/uia-action.ps1 -Title "Tao" -Name "Priority Medium" -Action select

param(
    [Parameter(Mandatory)] [string]$Title,
    [string]$Name,
    [string]$AutoId,
    [Parameter(Mandatory)] [ValidateSet('invoke','toggle','setvalue','select','setrange')] [string]$Action,
    [string]$Value
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

if ($AutoId) {
    $cond = New-Object System.Windows.Automation.PropertyCondition (
        [System.Windows.Automation.AutomationElement]::AutomationIdProperty, $AutoId)
} elseif ($Name) {
    $cond = New-Object System.Windows.Automation.PropertyCondition (
        [System.Windows.Automation.AutomationElement]::NameProperty, $Name)
} else { throw "Specify -Name or -AutoId." }
$el = $target.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
if (-not $el) { throw "Element not found." }
Write-Host "Element: $($el.Current.Name)" -ForegroundColor Cyan

switch ($Action) {
    'invoke' {
        $p = $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
        $p.Invoke()
    }
    'toggle' {
        $p = $el.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
        Write-Host ("Before: " + $p.Current.ToggleState) -ForegroundColor Yellow
        $p.Toggle()
    }
    'setvalue' {
        $p = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
        Write-Host ("Before: '" + $p.Current.Value + "'") -ForegroundColor Yellow
        $p.SetValue($Value)
    }
    'setrange' {
        $p = $el.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern)
        Write-Host ("Before: " + $p.Current.Value + " range=" + $p.Current.Minimum + ".." + $p.Current.Maximum) -ForegroundColor Yellow
        $p.SetValue([double]$Value)
    }
    'select' {
        $p = $el.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
        Write-Host ("Before: isSelected=" + $p.Current.IsSelected) -ForegroundColor Yellow
        $p.Select()
    }
}
Write-Host "Done." -ForegroundColor Green
