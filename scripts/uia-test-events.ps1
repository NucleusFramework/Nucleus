# All-in-one UIA event test for the Lab a11y-surface fixture (A11y tab).
#
# IMPORTANT: Legacy System.Windows.Automation.AutomationPropertyChangedEventHandler
# does NOT reliably receive events from AccessKit / ServerSideProvider fragment
# trees (see uia-problem.txt). Events DO leave the provider — verified with
# Interop.UIAutomationClient (CUIAutomation) via scripts/uia-listener.
#
# This script delegates to scripts/ci/verify-uia-events.ps1 (COM client gate).
#
# Usage:
#   # fixture already running: ./gradlew :examples:lab:app:run -Dlab.fixture=a11y-surface
#   pwsh scripts/uia-test-events.ps1 -Title "Nucleus A11y Surface"

param(
    [Parameter(Mandatory)] [string]$Title
)

$verify = Join-Path $PSScriptRoot "ci/verify-uia-events.ps1"
& $verify -Title $Title
exit $LASTEXITCODE
