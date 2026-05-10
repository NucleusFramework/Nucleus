# Triggers a UIA Invoke on a child element identified by name. Used to verify
# the round-trip: dump-uia-tree finds the button, then this script invokes it,
# then dump-uia-tree (or visual inspection) confirms Compose received the click.
#
# Usage:
#   pwsh scripts/uia-invoke.ps1 -Title "Tao Backend Demo" -Name "Clear"

param(
    [Parameter(Mandatory)] [string]$Title,
    [Parameter(Mandatory)] [string]$Name,
    [string]$ControlType
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
        if ($n -and $n.ToLower().Contains($Title.ToLower())) {
            $target = $w
            break
        }
    } catch {}
}
if (-not $target) { throw "Window '$Title' not found." }
Write-Host "Window: $($target.Current.Name)" -ForegroundColor Cyan

$cond = New-Object System.Windows.Automation.PropertyCondition (
    [System.Windows.Automation.AutomationElement]::NameProperty, $Name)
$btn = $target.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
if (-not $btn) { throw "Element named '$Name' not found." }
Write-Host "Element: $($btn.Current.Name) ($($btn.Current.ControlType.LocalizedControlType))" -ForegroundColor Cyan

$ip = $btn.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
if (-not $ip) { throw "Element does not support InvokePattern." }
$ip.Invoke()
Write-Host "Invoked." -ForegroundColor Green
