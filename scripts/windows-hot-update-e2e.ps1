<#
.SYNOPSIS
  End-to-end check of the Windows hot update: the app must never disappear from the screen while it
  updates itself.

.DESCRIPTION
  Installs the old NSIS installer silently into -InstallDir, serves the new one from a loopback
  update feed (jwebserver + a generated latest.yml), and launches the app with HOT_UPDATE_DEMO_FEED
  pointing at it; examples/hot-update-demo then downloads the update and calls installAndRestart on
  its own.

  While that runs, a sampler polls every few milliseconds:
    - the visible, non-cloaked top-level windows of processes started from -InstallDir (title and pid);
    - the pixel on screen at the centre of the app window, which is what the user actually sees.
  A sample with no app window, or a screen pixel that is not one of the demo's background colours,
  counts as a gap. The run fails if any gap is longer than -MaxGapMs after the first window appeared.

  -Mode classic sets -Dnucleus.updater.hotUpdate.disabled=true (through JAVA_TOOL_OPTIONS) to measure
  the close-install-relaunch update the hot update replaces.

  -Scenario picks what happens around the install (hot mode):
    update                   nothing: the app must never leave the screen, and the new version
                             must delete the retired version and launcher.
    relaunch-during-install  the launcher is started again mid-install, as a shortcut would: it must
                             exist and run (no "file not found"), and one window must be left.
    close-during-install     the window is closed mid-install: the app must not be relaunched, the
                             install must still complete, and the next start runs the new version
                             and deletes the retired one.
    failing-installer        the feed serves an installer that exits with code 3: the app must stay
                             on screen, on its version, with its launcher intact.
    stale-target-dir         versions\<new> already holds leftovers from an interrupted attempt.
    two-instances            two instances (single instance off), holding docA and docB, both start
                             the update at once: the install lock must serialize them, and each must
                             come back on the new version with its own document.
    notify-other-instance    as above, but only the docA instance checks the feed: the docB one must
                             learn about the update from pendingRestartVersion, downloading nothing,
                             and restart onto it with its document.

  Build the fixtures first:
    ./gradlew :examples:hot-update-demo:packageNsis -PhotUpdateDemoVersion=1.0.0   (copy the .exe aside)
    ./gradlew :examples:hot-update-demo:packageNsis -PhotUpdateDemoVersion=1.1.0

.EXAMPLE
  powershell -File scripts/windows-hot-update-e2e.ps1 -OldInstaller v1\hotupdatedemo-1.0.0-win-x64-nsis.exe `
    -NewInstaller v2\hotupdatedemo-1.1.0-win-x64-nsis.exe -NewVersion 1.1.0 -JdkHome $env:JAVA_HOME
#>
param(
    [Parameter(Mandatory)] [string] $OldInstaller,
    # One or more newer installers, applied in turn: the feed moves to the next one as soon as the
    # previous one is on screen, which chains hot updates (each started by a handed-over instance).
    [Parameter(Mandatory)] [string[]] $NewInstaller,
    [Parameter(Mandatory)] [string[]] $NewVersion,
    [Parameter(Mandatory)] [string] $JdkHome,
    [string] $InstallDir = "$env:TEMP\nucleus-hot-update-e2e\install",
    [ValidateSet('hot', 'classic')] [string] $Mode = 'hot',
    [ValidateSet('update', 'relaunch-during-install', 'close-during-install', 'failing-installer', 'stale-target-dir',
        'two-instances', 'notify-other-instance')]
    [string] $Scenario = 'update',
    [int] $Port = 8765,
    [int] $MaxGapMs = 0,
    [int] $TimeoutSeconds = 180,
    [string] $ReportDir = "$env:TEMP\nucleus-hot-update-e2e",
    # Extra JVM options for the app (e.g. a JUL config to trace the handoff).
    [string] $JavaToolOptions = ''
)

$ErrorActionPreference = 'Stop'
$exeName = 'HotUpdateDemo.exe'

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

public static class HotUpdateSampler {
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr p);
    delegate bool EnumProc(IntPtr h, IntPtr p);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] static extern IntPtr GetDC(IntPtr h);
    [DllImport("gdi32.dll")] static extern uint GetPixel(IntPtr dc, int x, int y);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out int v, int size);
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(int access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder s, ref int n);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }

    static readonly Dictionary<uint, string> paths = new Dictionary<uint, string>();
    public static int LastX = -1, LastY = -1;

    public static string Pixel(int x, int y) {
        SetProcessDPIAware();
        return GetPixel(GetDC(IntPtr.Zero), x, y).ToString("X6");
    }

    static string ImagePath(uint pid) {
        string p;
        if (paths.TryGetValue(pid, out p)) return p;
        p = "";
        IntPtr h = OpenProcess(0x1000, false, pid);
        if (h != IntPtr.Zero) {
            var sb = new StringBuilder(1024); int n = sb.Capacity;
            if (QueryFullProcessImageName(h, 0, sb, ref n)) p = sb.ToString();
            CloseHandle(h);
        }
        paths[pid] = p;
        return p;
    }

    // One line per sample: elapsedMs|pixelRGB|pid:title;pid:title...
    public static List<string> Run(string installDir, string titlePrefix, string stopFile, int timeoutMs) {
        SetProcessDPIAware();
        var lines = new List<string>();
        var sw = Stopwatch.StartNew();
        IntPtr screen = GetDC(IntPtr.Zero);
        int cx = -1, cy = -1;
        while (sw.ElapsedMilliseconds < timeoutMs && !System.IO.File.Exists(stopFile)) {
            var found = new List<string>();
            EnumWindows((h, _) => {
                if (!IsWindowVisible(h)) return true;
                int cloaked; DwmGetWindowAttribute(h, 14, out cloaked, 4);
                if (cloaked != 0) return true;
                var sb = new StringBuilder(256); GetWindowText(h, sb, 256);
                string title = sb.ToString();
                if (!title.StartsWith(titlePrefix)) return true;
                uint pid; GetWindowThreadProcessId(h, out pid);
                if (!ImagePath(pid).StartsWith(installDir, StringComparison.OrdinalIgnoreCase)) return true;
                RECT r; GetWindowRect(h, out r);
                // Left margin of the content: the background, clear of the centred text and the title bar.
                cx = r.L + 30; cy = (r.T + r.B) / 2;
                found.Add(pid + ":" + title);
                return true;
            }, IntPtr.Zero);
            string pixel = cx < 0 ? "-" : GetPixel(screen, cx, cy).ToString("X6");
            lines.Add(sw.ElapsedMilliseconds + "|" + pixel + "|" + string.Join(";", found));
            LastX = cx; LastY = cy;
            System.Threading.Thread.Sleep(5);
        }
        return lines;
    }
}
'@

function Get-Sha512Base64([string] $path) {
    $sha = [System.Security.Cryptography.SHA512]::Create()
    $stream = [System.IO.File]::OpenRead($path)
    try { [Convert]::ToBase64String($sha.ComputeHash($stream)) } finally { $stream.Dispose() }
}

function Stop-App {
    Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstallDir, 'OrdinalIgnoreCase') } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$feedDir = Join-Path $ReportDir 'feed'
Remove-Item $feedDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $feedDir | Out-Null

# --- Update feed -----------------------------------------------------------------------------
if ($NewInstaller.Count -ne $NewVersion.Count) { throw "-NewInstaller and -NewVersion must have the same length" }
for ($i = 0; $i -lt $NewInstaller.Count; $i++) {
    $installer = $NewInstaller[$i]
    $name = Split-Path $installer -Leaf
    Copy-Item $installer (Join-Path $feedDir $name)
    if (Test-Path "$installer.blockmap") { Copy-Item "$installer.blockmap" (Join-Path $feedDir "$name.blockmap") }
    $sha = Get-Sha512Base64 $installer
    $size = (Get-Item $installer).Length
    @"
version: $($NewVersion[$i])
files:
  - url: $name
    sha512: $sha
    size: $size
path: $name
sha512: $sha
releaseDate: '$(Get-Date -Format o)'
"@ | Set-Content -Encoding ascii (Join-Path $feedDir "latest-$i.yml")
}
if ($Scenario -eq 'failing-installer') {
    # A GUI-subsystem exe (no console flashes) that fails like a broken installer would.
    $fake = Join-Path $feedDir "fake-$($NewVersion[0])-win-x64-nsis.exe"
    Add-Type -OutputType WindowsApplication -OutputAssembly $fake -TypeDefinition @'
public static class FailingInstaller {
    public static int Main() { System.Threading.Thread.Sleep(2000); return 3; }
}
'@
    $sha = Get-Sha512Base64 $fake
    @"
version: $($NewVersion[0])
files:
  - url: $(Split-Path $fake -Leaf)
    sha512: $sha
    size: $((Get-Item $fake).Length)
"@ | Set-Content -Encoding ascii (Join-Path $feedDir 'latest-0.yml')
}
Copy-Item (Join-Path $feedDir 'latest-0.yml') (Join-Path $feedDir 'latest.yml')
$finalVersion = $NewVersion[-1]
$oldVersion = $null

$server = Start-Process -FilePath (Join-Path $JdkHome 'bin\jwebserver.exe') `
    -ArgumentList '-b', '127.0.0.1', '-p', "$Port", '-d', $feedDir -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 2

# --- Install the old version -----------------------------------------------------------------
Stop-App
if (Test-Path $InstallDir) {
    $uninstaller = Get-ChildItem $InstallDir -Filter 'Uninstall *.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($uninstaller) { Start-Process $uninstaller.FullName -ArgumentList '/S' -Wait }
    Remove-Item $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
}
Start-Process $OldInstaller -ArgumentList '/S', "/D=$InstallDir" -Wait
if (-not (Test-Path (Join-Path $InstallDir $exeName))) { throw "Old version was not installed into $InstallDir" }
$oldVersion = @(Get-ChildItem (Join-Path $InstallDir 'versions') -Directory -ErrorAction SilentlyContinue | ForEach-Object Name)[0]
$logFile = "$env:TEMP\hot-update-demo.log"
Remove-Item $logFile -ErrorAction SilentlyContinue
if ($Scenario -eq 'stale-target-dir') {
    $stale = Join-Path $InstallDir "versions\$($NewVersion[0])\app"
    New-Item -ItemType Directory -Force -Path $stale | Out-Null
    Set-Content (Join-Path $stale 'leftover.jar') 'not a jar'
}

# --- Run the app and sample the screen until the new version has taken over ------------------
$stopFile = Join-Path $ReportDir 'stop'
Remove-Item $stopFile -ErrorAction SilentlyContinue
$env:HOT_UPDATE_DEMO_FEED = "http://127.0.0.1:$Port"
$env:HOT_UPDATE_DEMO_TOPMOST = '1' # launched from a background process, the window would open behind others
$toolOptions = if ($Mode -eq 'classic') { "-Dnucleus.updater.hotUpdate.disabled=true $JavaToolOptions" } else { $JavaToolOptions }
if ($toolOptions.Trim()) { $env:JAVA_TOOL_OPTIONS = $toolOptions.Trim() } else { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }

$multiInstance = $Scenario -in 'two-instances', 'notify-other-instance'
$expectedWindows = if ($multiInstance) { 2 } else { 1 }
$watcher = Start-Job -ScriptBlock {
    param($stopFile, $feedDir, $versions, $timeout, $scenario, $logFile, $launcher, $reportDir, $expectedWindows)
    $deadline = (Get-Date).AddSeconds($timeout)
    $next = 0
    $seenAt = $null
    $actedAt = $null
    while ((Get-Date) -lt $deadline) {
        $processes = @(Get-Process -Name 'HotUpdateDemo' -ErrorAction SilentlyContinue)
        $titles = @($processes | ForEach-Object MainWindowTitle)
        if ($next -lt $versions.Count -and ($titles -contains "Hot Update Demo $($versions[$next])")) {
            $next++
            if ($next -lt $versions.Count) {
                Copy-Item (Join-Path $feedDir "latest-$next.yml") (Join-Path $feedDir 'latest.yml') -Force
            }
        }
        $onFinal = @($titles | Where-Object { $_ -eq "Hot Update Demo $($versions[-1])" }).Count
        if (-not $seenAt -and $next -ge $versions.Count -and $onFinal -ge $expectedWindows) { $seenAt = Get-Date }
        $installing = (Test-Path $logFile) -and (Select-String -Path $logFile -Pattern 'installAndRestart' -Quiet)
        if ($installing -and -not $actedAt) {
            $actedAt = Get-Date
            Start-Sleep -Milliseconds 1500 # the installer is running by now
            switch ($scenario) {
                'relaunch-during-install' {
                    try { Start-Process $launcher -ErrorAction Stop; 'ok' | Set-Content (Join-Path $reportDir 'relaunch.txt') }
                    catch { "error: $_" | Set-Content (Join-Path $reportDir 'relaunch.txt') }
                }
                'close-during-install' {
                    $processes | Where-Object MainWindowTitle | ForEach-Object { $_.CloseMainWindow() | Out-Null }
                    (Get-Date).ToString('o') | Set-Content (Join-Path $reportDir 'closed.txt')
                }
            }
        }
        # Keep sampling a few seconds after the last switch to catch a late disappearance.
        if ($seenAt -and ((Get-Date) - $seenAt).TotalSeconds -gt 6) { break }
        # No switch expected: watch long enough for the install to finish and a relaunch to show up.
        if ($actedAt -and $scenario -in 'close-during-install', 'failing-installer' -and ((Get-Date) - $actedAt).TotalSeconds -gt 25) { break }
        Start-Sleep -Milliseconds 200
    }
    New-Item -ItemType File -Path $stopFile -Force | Out-Null
} -ArgumentList $stopFile, $feedDir, $NewVersion, $TimeoutSeconds, $Scenario, $logFile, (Join-Path $InstallDir $exeName), $ReportDir, $expectedWindows
Remove-Item (Join-Path $ReportDir 'relaunch.txt'), (Join-Path $ReportDir 'closed.txt') -ErrorAction SilentlyContinue

$launcherPath = Join-Path $InstallDir $exeName
if ($multiInstance) {
    $env:HOT_UPDATE_DEMO_MULTI = '1'
    Start-Process $launcherPath -ArgumentList 'docA' | Out-Null
    if ($Scenario -eq 'notify-other-instance') { $env:HOT_UPDATE_DEMO_CHECK = '0' }
    Start-Process $launcherPath -ArgumentList 'docB' | Out-Null
    Remove-Item Env:HOT_UPDATE_DEMO_MULTI, Env:HOT_UPDATE_DEMO_CHECK -ErrorAction SilentlyContinue
} else {
    Start-Process $launcherPath | Out-Null
}
$samples = [HotUpdateSampler]::Run($InstallDir, 'Hot Update Demo', $stopFile, $TimeoutSeconds * 1000)
Wait-Job $watcher | Out-Null
Remove-Item Env:HOT_UPDATE_DEMO_FEED, Env:HOT_UPDATE_DEMO_TOPMOST, Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
$samples | Set-Content (Join-Path $ReportDir "samples-$Mode-$Scenario.txt")
$cfgAfterRun = Get-Content (Join-Path $InstallDir "app\$([IO.Path]::GetFileNameWithoutExtension($exeName)).cfg") -Raw
$launcherExists = Test-Path (Join-Path $InstallDir $exeName)

if ($Scenario -eq 'close-during-install') {
    # The user starts the app again later: the new version must run and retire the old one.
    $env:HOT_UPDATE_DEMO_FEED = "http://127.0.0.1:$Port"
    Start-Process (Join-Path $InstallDir $exeName) | Out-Null
    Remove-Item Env:HOT_UPDATE_DEMO_FEED -ErrorAction SilentlyContinue
    $restartDeadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $restartDeadline -and -not (Get-Process -Name 'HotUpdateDemo' -ErrorAction SilentlyContinue |
            Where-Object MainWindowTitle -eq "Hot Update Demo $finalVersion")) { Start-Sleep -Milliseconds 200 }
}

# What the screen shows at the app's position once it is gone: the reference for a gap.
Start-Sleep -Seconds 3 # let the new version clean the retired one up
$versions = @(Get-ChildItem (Join-Path $InstallDir 'versions') -Directory -ErrorAction SilentlyContinue | ForEach-Object Name)
$retired = @(Get-ChildItem $InstallDir -Filter '*.nucleus-old' -ErrorAction SilentlyContinue | ForEach-Object Name)
$running = @(Get-Process -Name 'HotUpdateDemo' -ErrorAction SilentlyContinue | Where-Object MainWindowTitle | ForEach-Object MainWindowTitle)
$processes = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstallDir, 'OrdinalIgnoreCase') } |
    ForEach-Object { "$($_.ProcessId)<-$($_.ParentProcessId):$(Split-Path $_.ExecutablePath -Leaf)" })
Stop-App
Start-Sleep -Milliseconds 800
$background = [HotUpdateSampler]::Pixel([HotUpdateSampler]::LastX, [HotUpdateSampler]::LastY)

# --- Analyse ---------------------------------------------------------------------------------
$appColors = @('C06515', '327D2E', '9A1B6A', '2828C6') # demo palette, as GetPixel's 0x00BBGGRR

function Get-Distance([string] $a, [string] $b) {
    $x = [Convert]::ToInt32($a, 16); $y = [Convert]::ToInt32($b, 16)
    $d = 0
    foreach ($shift in 0, 8, 16) { $d += [math]::Abs((($x -shr $shift) -band 255) - (($y -shr $shift) -band 255)) }
    $d
}

# The window manager cross-fades a window it shows or hides, so the pixel passes through blends of
# the two versions' colours: a sample is a gap only when it is closer to the background than to the app.
function Test-AppVisible([string] $pixel) {
    if ($pixel -eq '-') { return $false }
    if ($appColors -contains $pixel) { return $true }
    $toApp = ($appColors | ForEach-Object { Get-Distance $pixel $_ } | Measure-Object -Minimum).Minimum
    $toApp -lt (Get-Distance $pixel $background)
}
$firstSeen = $null; $newSeen = $null; $lastT = 0
$gaps = @(); $gapStart = $null; $screenSeen = $false; $overlapMs = 0; $pixelGaps = @(); $pixelGapStart = $null
foreach ($line in $samples) {
    $parts = $line.Split('|', 3)
    $t = [long]$parts[0]; $pixel = $parts[1]; $windows = $parts[2]
    $titles = @($windows.Split(';', [StringSplitOptions]::RemoveEmptyEntries) | ForEach-Object { $_.Split(':', 2)[1] })
    if ($titles.Count -gt 0 -and -not $firstSeen) { $firstSeen = $t }
    if (-not $newSeen -and ($titles -contains "Hot Update Demo $finalVersion")) { $newSeen = $t }
    if ($firstSeen) {
        if ($titles.Count -eq 0) { if (-not $gapStart) { $gapStart = $t } }
        elseif ($gapStart) { $gaps += ($t - $gapStart); $gapStart = $null }
        if (($titles | Select-Object -Unique).Count -gt 1) { $overlapMs += ($t - $lastT) }
        # Counted from the first frame the app is really on screen: the window is reported
        # visible while the window manager is still fading it in at startup.
        $onScreen = Test-AppVisible $pixel
        if ($onScreen) { $screenSeen = $true }
        if ($screenSeen) {
            if (-not $onScreen) { if (-not $pixelGapStart) { $pixelGapStart = $t } }
            elseif ($pixelGapStart) { $pixelGaps += ($t - $pixelGapStart); $pixelGapStart = $null }
        }
    }
    $lastT = $t
}
if ($gapStart) { $gaps += ($lastT - $gapStart) }
if ($pixelGapStart) { $pixelGaps += ($lastT - $pixelGapStart) }
$maxGap = ($gaps + 0 | Measure-Object -Maximum).Maximum
$maxPixelGap = ($pixelGaps + 0 | Measure-Object -Maximum).Maximum
$intervals = for ($i = 1; $i -lt $samples.Count; $i++) { [long]$samples[$i].Split('|')[0] - [long]$samples[$i - 1].Split('|')[0] }
$avgInterval = [math]::Round(($intervals | Measure-Object -Average).Average, 1)

Write-Host "mode=$Mode scenario=$Scenario installDir=$InstallDir samples=$($samples.Count) avgIntervalMs=$avgInterval"
Write-Host "firstWindowMs=$firstSeen newVersionWindowMs=$newSeen"
Write-Host "windowGaps=$($gaps.Count) maxWindowGapMs=$maxGap"
Write-Host "screenGaps=$($pixelGaps.Count) maxScreenGapMs=$maxPixelGap"
Write-Host "overlapMs=$overlapMs backgroundPixel=$background"
Write-Host "versionsLeft=$($versions -join ',') retiredLaunchersLeft=$($retired -join ',')"
Write-Host "runningWindows=$($running -join ',')"
Write-Host "processes=$($processes -join ' ')"
Get-Content "$env:TEMP\hot-update-demo.log" -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  app: $_" }

Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue

$failures = @()
$finalWindows = @($running | Where-Object { $_ -like 'Hot Update Demo*' })
$appLog = @(Get-Content $logFile -Encoding UTF8 -ErrorAction SilentlyContinue)
$expectedCommand = "command=$(Join-Path $InstallDir $exeName)"
if (-not $launcherExists) { $failures += "the launcher $exeName was missing after the run" }
if ($Scenario -ne 'failing-installer' -and ($cfgAfterRun -notmatch [regex]::Escape("versions\$finalVersion\runtime"))) {
    $failures += "the launcher cfg does not start $finalVersion"
}
# Every start, the handed-over ones included, runs from the stable launcher path (autostart,
# protocol handlers and shortcuts registered by the app keep pointing at something that exists).
$badCommand = @($appLog | Where-Object { $_ -match ' started ' -and $_ -notmatch [regex]::Escape($expectedCommand) })
if ($badCommand.Count -gt 0) { $failures += "a start did not run from $expectedCommand : $($badCommand -join ' | ')" }

$screenVerified = @($samples | Where-Object { $appColors -contains $_.Split('|')[1] }).Count -gt 0
function Test-NoGap {
    if ($maxGap -gt $MaxGapMs) { $script:failures += "the app had no window on screen for $maxGap ms" }
    # The app window is topmost: if the screen never showed it once, the desktop is not being
    # composed (display off, session locked) and the screen check says nothing either way.
    if (-not $screenVerified) {
        Write-Host "WARNING: the screen never showed the app (display off or session locked?); screen check skipped"
    } elseif ($maxPixelGap -gt $MaxGapMs) {
        $script:failures += "the app was not visible at its position for $maxPixelGap ms"
    }
}
function Test-CleanedUp {
    if ($versions.Count -ne 1) { $script:failures += "retired versions were not cleaned up: $($versions -join ',')" }
    if ($retired.Count -ne 0) { $script:failures += "retired launchers were not cleaned up: $($retired -join ',')" }
}

switch ($Scenario) {
    { $_ -in 'update', 'relaunch-during-install', 'stale-target-dir' } {
        if (-not $newSeen) { $failures += "the new version never showed a window" }
        if ($finalWindows.Count -ne 1) { $failures += "expected one app window at the end, got: $($finalWindows -join ',')" }
        Test-NoGap
        if ($Mode -eq 'hot') { Test-CleanedUp }
        if ($_ -eq 'relaunch-during-install') {
            $relaunch = Get-Content (Join-Path $ReportDir 'relaunch.txt') -ErrorAction SilentlyContinue
            Write-Host "relaunchDuringInstall=$relaunch"
            if ($relaunch -ne 'ok') { $failures += "starting the launcher during the install failed: $relaunch" }
        }
    }
    'close-during-install' {
        # Samples after the window closed must stay empty: the app was quit, it must not come back.
        $closedAt = $null; $reappeared = $null; $shown = $false
        foreach ($line in $samples) {
            $parts = $line.Split('|', 3); $t = [long]$parts[0]
            if ($parts[2]) { $shown = $true }
            if ($shown -and -not $closedAt -and -not $parts[2]) { $closedAt = $t }
            if ($closedAt -and $parts[2]) { $reappeared = "$t ms: $($parts[2])"; break }
        }
        Write-Host "closedAtMs=$closedAt reappeared=$reappeared"
        if (-not $closedAt) { $failures += "the window was never closed" }
        if ($reappeared) { $failures += "the app came back after the user closed it ($reappeared)" }
        if ($finalWindows -notcontains "Hot Update Demo $finalVersion") { $failures += "the next start did not run $finalVersion" }
        Test-CleanedUp
    }
    { $_ -in 'two-instances', 'notify-other-instance' } {
        if (-not $newSeen) { $failures += "the new version never showed a window" }
        $onFinal = @($finalWindows | Where-Object { $_ -eq "Hot Update Demo $finalVersion" })
        if ($onFinal.Count -ne 2) { $failures += "expected two $finalVersion windows at the end, got: $($finalWindows -join ',')" }
        Test-NoGap
        Test-CleanedUp
        foreach ($doc in 'docA', 'docB') {
            if (-not ($appLog | Where-Object { $_ -match "started version=$([regex]::Escape($finalVersion)) args=\[$doc\]" })) {
                $failures += "no $finalVersion instance came back with $doc"
            }
        }
        $installs = @($appLog | Where-Object { $_ -match 'installAndRestart' }).Count
        Write-Host "installAndRestartCalls=$installs pendingRestart=$(@($appLog | Where-Object { $_ -match 'pendingRestart' }).Count)"
        if ($_ -eq 'notify-other-instance') {
            if (-not ($appLog | Where-Object { $_ -match "pendingRestart $([regex]::Escape($finalVersion))" })) {
                $failures += "the docB instance never learned about the installed update"
            }
            if ($installs -ne 1) { $failures += "expected one install (docA), got $installs" }
        }
    }
    'failing-installer' {
        if ($newSeen) { $failures += "a new version showed up although the installer failed" }
        if ($finalWindows -notcontains "Hot Update Demo $oldVersion") { $failures += "the app did not stay on $oldVersion" }
        if (@($samples | Where-Object { $_ -match "Hot Update Demo $oldVersion" } | ForEach-Object { $_.Split('|')[2].Split(':')[0] } | Select-Object -Unique).Count -ne 1) {
            $failures += "the app process changed although the installer failed"
        }
        if ($versions -join ',' -ne $oldVersion) { $failures += "the versions directory changed: $($versions -join ',')" }
        Test-NoGap
    }
}
if ($failures.Count -gt 0) { Write-Host "FAILED: $($failures -join '; ')"; exit 1 }
Write-Host "PASSED"
