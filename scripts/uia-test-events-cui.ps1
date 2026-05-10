# Inline-compiled C# UIA event listener using CUIAutomation8 (the COM v3
# client) directly, bypassing the legacy System.Windows.Automation managed
# wrapper. This is the equivalent of what AccEvent.exe uses.
#
# Usage:
#   pwsh scripts/uia-test-events-cui.ps1 -Title "Tao Backend"

param([Parameter(Mandatory)] [string]$Title)

# Force MTA (UIA threading guidance: event handlers should run on MTA).
if ([System.Threading.Thread]::CurrentThread.GetApartmentState() -ne 'MTA') {
    Write-Host "Re-launching in MTA..." -ForegroundColor DarkGray
    & powershell.exe -MTA -ExecutionPolicy Bypass -File $PSCommandPath -Title $Title
    exit
}

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

# Use the modern Interop.UIAutomationClient via reflection. The CUIAutomation8
# COM class is registered under CLSID {E22AD333-B25F-460C-83D0-0581107395C9}.
$type = [Type]::GetTypeFromCLSID([Guid]"E22AD333-B25F-460C-83D0-0581107395C9")
$uia = [Activator]::CreateInstance($type)

# Find the target window by title substring among top-level children of the
# desktop root.
$desktop = $uia.GetRootElement()

$treeWalker = $uia.RawViewWalker
$child = $treeWalker.GetFirstChildElement($desktop)
$target = $null
while ($child) {
    try {
        $n = $child.CurrentName
        if ($n -and $n.ToLower().Contains($Title.ToLower())) { $target = $child; break }
    } catch {}
    $child = $treeWalker.GetNextSiblingElement($child)
}
if (-not $target) { throw "Window '$Title' not found." }
Write-Host "Target: $($target.CurrentName)" -ForegroundColor Cyan

# Subscribe via raw COM. We use a C# proxy class to capture the event callback,
# since PowerShell's [scriptblock] -> COM interface marshalling for IUIAutomation*
# is inconsistent.
Add-Type -ReferencedAssemblies UIAutomationClient,UIAutomationTypes -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Collections.Generic;

[ComImport, Guid("40CD37D4-C756-4B0C-8C6F-BDDFEEB13B50"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IUIAutomationPropertyChangedEventHandler {
    [PreserveSig]
    int HandlePropertyChangedEvent([MarshalAs(UnmanagedType.IUnknown)] object sender, int propertyId, object newValue);
}

[ComImport, Guid("169E2EF6-DE85-4F00-99B6-A2D88EF11AAB"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IUIAutomationFocusChangedEventHandler {
    [PreserveSig]
    int HandleFocusChangedEvent([MarshalAs(UnmanagedType.IUnknown)] object sender);
}

[ComImport, Guid("C7CB2637-E6C2-4D0C-85DE-4948C02175C7"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IUIAutomationStructureChangedEventHandler {
    [PreserveSig]
    int HandleStructureChangedEvent([MarshalAs(UnmanagedType.IUnknown)] object sender, int changeType, int[] runtimeId);
}

public class PropHandler : IUIAutomationPropertyChangedEventHandler {
    public List<string> Captured = new List<string>();
    public int HandlePropertyChangedEvent(object sender, int propertyId, object newValue) {
        lock (Captured) Captured.Add(string.Format("[Prop] propId={0} new={1}", propertyId, newValue));
        return 0;
    }
}
public class FocusHandler : IUIAutomationFocusChangedEventHandler {
    public List<string> Captured = new List<string>();
    public int HandleFocusChangedEvent(object sender) {
        lock (Captured) Captured.Add("[Focus]");
        return 0;
    }
}
public class StructureHandler : IUIAutomationStructureChangedEventHandler {
    public List<string> Captured = new List<string>();
    public int HandleStructureChangedEvent(object sender, int changeType, int[] runtimeId) {
        lock (Captured) Captured.Add(string.Format("[Struct] type={0}", changeType));
        return 0;
    }
}
"@

$propHandler = New-Object PropHandler
$focusHandler = New-Object FocusHandler
$structHandler = New-Object StructureHandler

# AddPropertyChangedEventHandlerNativeArray(element, scope, cacheRequest, handler, propertyArray, propertyCount)
# scope: TreeScope_Subtree = 7
$props = @(30005, 30045, 30086, 30047, 30079) # Name,Value,Toggle,RangeValue,SelectionItem
$uia.AddPropertyChangedEventHandlerNativeArray($target, 7, $null, $propHandler, $props, $props.Length)
$uia.AddFocusChangedEventHandler($null, $focusHandler)
$uia.AddStructureChangedEventHandler($target, 7, $null, $structHandler)

Start-Sleep -Milliseconds 500
Write-Host "Subscribed (CUIAutomation MTA). Triggering actions..." -ForegroundColor Yellow
Start-Sleep -Seconds 1

function FindByName($name) {
    $cond = $uia.CreatePropertyCondition(30005, $name)
    return $target.FindFirst(4, $cond) # TreeScope_Descendants = 4
}

$el = FindByName 'Tri-state checkbox'
if ($el) {
    $tp = $el.GetCurrentPattern(10015) # UIA_TogglePatternId
    if ($tp) { [System.Runtime.InteropServices.Marshal]::GetObjectForIUnknown(
        [System.Runtime.InteropServices.Marshal]::GetIUnknownForObject($tp)) | Out-Null }
    Write-Host "Toggling Tri-state checkbox" -ForegroundColor Cyan
    $tp.Toggle()
}
Start-Sleep -Seconds 1
$el = FindByName 'Increment'
if ($el) {
    $ip = $el.GetCurrentPattern(10000) # UIA_InvokePatternId
    Write-Host "Invoking Increment" -ForegroundColor Cyan
    $ip.Invoke()
}
Start-Sleep -Seconds 5

$uia.RemoveAllEventHandlers()

Write-Host ""
Write-Host "=== Captured ===" -ForegroundColor Green
foreach ($l in $propHandler.Captured)   { Write-Host $l }
foreach ($l in $focusHandler.Captured)  { Write-Host $l }
foreach ($l in $structHandler.Captured) { Write-Host $l }
Write-Host ("Total: prop={0} focus={1} struct={2}" -f $propHandler.Captured.Count, $focusHandler.Captured.Count, $structHandler.Captured.Count) -ForegroundColor Yellow
