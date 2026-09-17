# All-in-one UIA event test for tao-demo A11y tab.
#
# IMPORTANT: Legacy System.Windows.Automation.AutomationPropertyChangedEventHandler
# does NOT reliably receive events from AccessKit / ServerSideProvider fragment
# trees (see uia-problem.txt). Events DO leave the provider — verified with
# Interop.UIAutomationClient (CUIAutomation) via scripts/uia-listener.
#
# This script delegates to scripts/ci/verify-uia-events.ps1 (COM client gate).
#
# Usage:
#   # demo already running with NUCLEUS_DEMO_TAB=A11y
#   pwsh scripts/uia-test-events.ps1 -Title "Tao Backend Demo"

param(
    [Parameter(Mandatory)] [string]$Title
)

$verify = Join-Path $PSScriptRoot "ci/verify-uia-events.ps1"
& $verify -Title $Title
exit $LASTEXITCODE
