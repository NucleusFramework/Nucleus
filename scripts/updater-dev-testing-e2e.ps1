<#
.SYNOPSIS
  End-to-end check of the updater's dev-testing switches on a real, installed NSIS app: updating
  without publishing anything, simulating updates, and refusing both when the app does not opt in.

.DESCRIPTION
  Uses examples/hot-update-demo, which ships a production GitHubProvider (never contacted here),
  sets UpdaterConfig.allowLaunchOverrides unless HOT_UPDATE_DEMO_ALLOW_OVERRIDES=0, and logs every
  step of its update flow to %TEMP%\hot-update-demo.log. Each scenario reinstalls the old version
  silently into -InstallDir, starts it with the switch under test, and reads the log.

  Scenarios (all by default):
    file-feed            NUCLEUS_UPDATER_FEED_URL=<packaging output dir of the new version>: the
                         installed app updates to it and restarts on it.
    file-url-feed        the same through a file: URL of a copy in a directory whose name has
                         spaces and non-ASCII characters.
    http-feed            ./gradlew serveUpdateFeed (throttled, so the download reports progress
                         along the way) and NUCLEUS_UPDATER_FEED_URL=http://127.0.0.1:<port>.
    http-feed-cached     the same again: the update cache now holds the new installer, so the
                         download must be differential (block map + range requests over the task).
    locked               HOT_UPDATE_DEMO_ALLOW_OVERRIDES=0: the redirect is ignored, nothing updates.
    simulate             NUCLEUS_UPDATER_SIMULATE=update: a simulated update is offered, downloaded,
                         and its install skipped; the app keeps running on its version.
    simulate-error       NUCLEUS_UPDATER_SIMULATE=checksum-error: the download fails as a
                         tampered artifact would.
    simulate-updated     NUCLEUS_UPDATER_SIMULATE_JUST_UPDATED_FROM=0.9.0: the post-update event.
    run-simulate         ./gradlew run -Pnucleus.updater.simulate=download-error (unpackaged).
    run-feed             ./gradlew run -Pnucleus.updater.feedUrl=<new version dir>: an unpackaged
                         run checks and downloads, and skips the install (the installed app is
                         left alone).

  Build the fixtures first:
    ./gradlew :examples:hot-update-demo:packageNsis -PhotUpdateDemoVersion=1.0.0   (copy the .exe aside)
    ./gradlew :examples:hot-update-demo:packageNsis -PhotUpdateDemoVersion=1.1.0

.EXAMPLE
  powershell -File scripts/updater-dev-testing-e2e.ps1 -OldInstaller v1\HotUpdateDemo-1.0.0-win-x64-nsis.exe `
    -OldVersion 1.0.0 -NewVersion 1.1.0
#>
param(
    [Parameter(Mandatory)] [string] $OldInstaller,
    [Parameter(Mandatory)] [string] $OldVersion,
    [Parameter(Mandatory)] [string] $NewVersion,
    [string] $RepoRoot = '',
    # Defaults to the hot-update-demo NSIS packaging output: the directory is the feed.
    [string] $NewFeedDir = '',
    [string[]] $Scenario = @('file-feed', 'file-url-feed', 'http-feed', 'http-feed-cached', 'locked', 'simulate',
        'simulate-error', 'simulate-updated', 'run-simulate', 'run-feed'),
    [string] $InstallDir = "$env:TEMP\nucleus-updater-dev-e2e\install",
    [string] $ReportDir = "$env:TEMP\nucleus-updater-dev-e2e",
    [int] $Port = 8431,
    [int] $TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
# Script-relative defaults: $PSScriptRoot is not set yet while Windows PowerShell binds parameters.
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
# `powershell -File` passes `-Scenario a,b` as the single string "a,b".
$Scenario = @($Scenario | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
if (-not $NewFeedDir) { $NewFeedDir = Join-Path $RepoRoot 'examples\hot-update-demo\build\compose\binaries\main\nsis' }
$exeName = 'HotUpdateDemo.exe'
$logFile = "$env:TEMP\hot-update-demo.log"
$gradlew = Join-Path $RepoRoot 'gradlew.bat'
$switches = 'NUCLEUS_UPDATER_FEED_URL', 'NUCLEUS_UPDATER_SIMULATE', 'NUCLEUS_UPDATER_SIMULATE_DURATION',
    'NUCLEUS_UPDATER_SIMULATE_JUST_UPDATED_FROM', 'HOT_UPDATE_DEMO_ALLOW_OVERRIDES', 'HOT_UPDATE_DEMO_FEED', 'JAVA_TOOL_OPTIONS'
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
if (-not (Get-ChildItem $NewFeedDir -Filter 'latest.yml' -ErrorAction SilentlyContinue)) { throw "No latest.yml in $NewFeedDir" }
if (-not (Test-Path $OldInstaller)) { throw "No old installer at $OldInstaller" }

function Stop-App {
    Get-CimInstance Win32_Process | Where-Object {
        ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($InstallDir, 'OrdinalIgnoreCase')) -or
            ($_.CommandLine -and $_.CommandLine -match 'hotupdatedemo\.MainKt')
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
}

function Clear-Switches { foreach ($name in $switches) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue } }

function Install-Old {
    Stop-App
    if (Test-Path $InstallDir) {
        $uninstaller = Get-ChildItem $InstallDir -Filter 'Uninstall *.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($uninstaller) { Start-Process $uninstaller.FullName -ArgumentList '/S' -Wait }
        Remove-Item $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    Start-Process (Resolve-Path $OldInstaller) -ArgumentList '/S', "/D=$InstallDir" -Wait
    if (-not (Test-Path (Join-Path $InstallDir $exeName))) { throw "The old version was not installed into $InstallDir" }
}

function Installed-Versions { @(Get-ChildItem (Join-Path $InstallDir 'versions') -Directory -ErrorAction SilentlyContinue | ForEach-Object Name) }

function Read-Log { @(Get-Content $logFile -Encoding UTF8 -ErrorAction SilentlyContinue) }

# Waits until a log line matches every pattern in turn (in order), or the timeout.
function Wait-Log([string[]] $patterns, [int] $seconds = $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $lines = Read-Log; $i = 0
        foreach ($line in $lines) { if ($i -lt $patterns.Count -and $line -match $patterns[$i]) { $i++ } }
        if ($i -eq $patterns.Count) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

function Start-Installed {
    Remove-Item $logFile -ErrorAction SilentlyContinue
    Start-Process (Join-Path $InstallDir $exeName) | Out-Null
}

function Start-Gradle([string[]] $arguments, [string] $name) {
    $out = Join-Path $ReportDir "$name.gradle.log"
    Start-Process -FilePath $gradlew -ArgumentList ($arguments + '--console=plain') -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $out -RedirectStandardError "$out.err" -PassThru -WindowStyle Hidden
}

function Stop-FeedServer {
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
}

function Wait-Feed([int] $seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try { Invoke-WebRequest "http://127.0.0.1:$Port/latest.yml" -UseBasicParsing -TimeoutSec 2 | Out-Null; return $true } catch { Start-Sleep 1 }
    }
    return $false
}

$old = [regex]::Escape($OldVersion); $new = [regex]::Escape($NewVersion)
$updatedPatterns = @("started version=$old ", 'installAndRestart ', "started version=$new ", "updated from $old to $new")
$results = [ordered]@{}

foreach ($name in $Scenario) {
    Write-Host "=== $name"
    Clear-Switches
    $failures = @()
    try {
        switch ($name) {
            'file-feed' {
                Install-Old
                $env:NUCLEUS_UPDATER_FEED_URL = (Resolve-Path $NewFeedDir).Path
                Start-Installed
                if (-not (Wait-Log $updatedPatterns)) { $failures += 'the installed app did not update from the local directory' }
                if ((Installed-Versions) -notcontains $NewVersion) { $failures += "versions\$NewVersion is not installed: $(Installed-Versions)" }
            }
            'file-url-feed' {
                Install-Old
                $odd = Join-Path $ReportDir "feed dir ünïcødé"
                Remove-Item $odd -Recurse -Force -ErrorAction SilentlyContinue
                New-Item -ItemType Directory -Force -Path $odd | Out-Null
                Get-ChildItem $NewFeedDir -File | Where-Object { $_.Name -match '\.(exe|yml|blockmap)$' } | Copy-Item -Destination $odd
                $env:NUCLEUS_UPDATER_FEED_URL = ([System.Uri] (Resolve-Path $odd).Path).AbsoluteUri
                Write-Host "feed=$env:NUCLEUS_UPDATER_FEED_URL"
                Start-Installed
                if (-not (Wait-Log $updatedPatterns)) { $failures += 'the installed app did not update from the file: URL' }
            }
            { $_ -in 'http-feed', 'http-feed-cached' } {
                if ($_ -eq 'http-feed') {
                    # A cold cache: the whole installer crosses the (throttled) link.
                    Remove-Item "$env:LOCALAPPDATA\nucleus\updates" -Recurse -Force -ErrorAction SilentlyContinue
                }
                Install-Old
                Stop-FeedServer
                # The cached run repackages the new version: electron-builder output is not byte-for-byte
                # reproducible, so the cached installer is a real, slightly different, delta basis.
                $repackage = if ($_ -eq 'http-feed-cached') { @(':examples:hot-update-demo:packageNsis', '--rerun') } else { @() }
                $gradle = Start-Gradle ($repackage + @(':examples:hot-update-demo:serveUpdateFeed', "-PhotUpdateDemoVersion=$NewVersion",
                    "-Pnucleus.updater.serve.port=$Port", '-Pnucleus.updater.serve.throttle=6m',
                    '-Pnucleus.updater.serve.timeout=240')) $_
                if (-not (Wait-Feed 300)) { throw "serveUpdateFeed did not come up (see $ReportDir\$_.gradle.log)" }
                $env:NUCLEUS_UPDATER_FEED_URL = "http://127.0.0.1:$Port"
                Start-Installed
                if (-not (Wait-Log $updatedPatterns)) { $failures += 'the installed app did not update from serveUpdateFeed' }
                $downloaded = Read-Log | Where-Object { $_ -match 'downloaded .* differential=(\w+) reports=(\d+)' } | Select-Object -First 1
                Write-Host "download: $downloaded"
                if ($downloaded -match 'differential=(\w+) reports=(\d+)') {
                    $differential = $Matches[1]; $reports = [int]$Matches[2]
                    if ($_ -eq 'http-feed') {
                        if ($differential -ne 'false') { $failures += 'a cold-cache update should be a full download' }
                        if ($reports -lt 10) { $failures += "a throttled download should report progress along the way, got $reports reports" }
                    } elseif ($differential -ne 'true') {
                        $failures += 'with the new installer cached, the update should be differential'
                    }
                } else { $failures += 'no download line in the app log' }
                $served = Get-Content (Join-Path $ReportDir "$_.gradle.log") -ErrorAction SilentlyContinue
                if (-not ($served -match 'GET /latest\.yml')) { $failures += 'serveUpdateFeed never served latest.yml' }
                if ($_ -eq 'http-feed-cached' -and -not ($served -match '\[bytes=')) { $failures += 'no range request reached serveUpdateFeed' }
                Stop-FeedServer
                $gradle | Stop-Process -Force -ErrorAction SilentlyContinue
            }
            'locked' {
                Install-Old
                $env:HOT_UPDATE_DEMO_ALLOW_OVERRIDES = '0'
                $env:NUCLEUS_UPDATER_FEED_URL = (Resolve-Path $NewFeedDir).Path
                Start-Installed
                # The refused redirect leaves the production provider (a GitHub repo that does not exist).
                $expected = @("started version=$old ", 'updater feedOverride=null simulation=null', 'no update \(Error')
                if (-not (Wait-Log $expected 60)) { $failures += 'the updater did not keep its production provider' }
                Start-Sleep -Seconds 10
                $log = Read-Log
                if ($log -match "started version=$new ") { $failures += 'the app updated although it does not allow launch overrides' }
                if ($log -match 'downloaded ') { $failures += 'something was downloaded' }
                if ((Installed-Versions) -contains $NewVersion) { $failures += "versions\$NewVersion appeared" }
            }
            'simulate' {
                Install-Old
                $env:NUCLEUS_UPDATER_SIMULATE = 'update'
                $env:NUCLEUS_UPDATER_SIMULATE_DURATION = '2'
                Start-Installed
                $expected = @("started version=$old ", 'simulation=UpdateSimulation\(scenario=UPDATE_AVAILABLE', 'downloaded simulated-update-', 'installAndRestart returned')
                if (-not (Wait-Log $expected 60)) { $failures += 'the simulated update did not play through' }
                Start-Sleep -Seconds 3
                $log = Read-Log
                # -match on an array filters it and leaves $Matches alone: match the line itself.
                $downloadLine = @($log | Where-Object { $_ -match 'downloaded simulated-update-' })[0]
                if (-not ($downloadLine -match 'reports=(\d+)') -or [int]$Matches[1] -lt 10) {
                    $failures += "the simulated download reported too little progress: $downloadLine"
                }
                if ($log -match "started version=$new ") { $failures += 'a simulated update restarted the app' }
                $alive = Get-Process -Name 'HotUpdateDemo' -ErrorAction SilentlyContinue
                if (-not $alive) { $failures += 'the app exited after a simulated install' }
                if ((Installed-Versions) -contains $NewVersion) { $failures += 'a simulated update installed something' }
            }
            'simulate-error' {
                Install-Old
                $env:NUCLEUS_UPDATER_SIMULATE = 'checksum-error'
                $env:NUCLEUS_UPDATER_SIMULATE_DURATION = '1'
                Start-Installed
                if (-not (Wait-Log @("started version=$old ", 'download failed: .*ChecksumException') 60)) { $failures += 'the simulated checksum failure did not surface' }
                if ((Read-Log) -match 'installAndRestart') { $failures += 'a failed download went on to install' }
            }
            'simulate-updated' {
                Install-Old
                $env:NUCLEUS_UPDATER_SIMULATE_JUST_UPDATED_FROM = '0.9.0'
                Start-Installed
                if (-not (Wait-Log @("started version=$old ", "updated from 0\.9\.0 to $old") 60)) { $failures += 'the simulated post-update launch was not reported' }
            }
            'run-simulate' {
                Stop-App
                Remove-Item $logFile -ErrorAction SilentlyContinue
                $gradle = Start-Gradle @(':examples:hot-update-demo:run', "-PhotUpdateDemoVersion=$OldVersion",
                    '-Pnucleus.updater.simulate=download-error', '-Pnucleus.updater.simulate.duration=1') $name
                if (-not (Wait-Log @("started version=$old ", 'supported=true', 'download failed: .*NetworkException') 300)) {
                    $failures += 'the simulated download failure did not surface in ./gradlew run'
                }
                Stop-App
                $gradle | Stop-Process -Force -ErrorAction SilentlyContinue
            }
            'run-feed' {
                Install-Old
                $before = Installed-Versions
                Remove-Item $logFile -ErrorAction SilentlyContinue
                $gradle = Start-Gradle @(':examples:hot-update-demo:run', "-PhotUpdateDemoVersion=$OldVersion",
                    "-Pnucleus.updater.feedUrl=$((Resolve-Path $NewFeedDir).Path)") $name
                if (-not (Wait-Log @("started version=$old ", 'supported=true', 'downloaded .*nsis\.exe', 'installAndRestart returned') 300)) {
                    $failures += 'the unpackaged run did not check and download from the redirected feed'
                }
                Start-Sleep -Seconds 2
                if (-not (Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'hotupdatedemo\.MainKt' })) {
                    $failures += 'the unpackaged run exited instead of skipping the install'
                }
                if ((Installed-Versions) -join ',' -ne ($before -join ',')) { $failures += 'the unpackaged run touched the installed app' }
                Stop-App
                $gradle | Stop-Process -Force -ErrorAction SilentlyContinue
            }
            default { throw "Unknown scenario $name" }
        }
    } catch {
        $failures += "error: $_"
    }
    Copy-Item $logFile (Join-Path $ReportDir "$name.app.log") -ErrorAction SilentlyContinue
    Stop-App
    $results[$name] = if ($failures.Count -eq 0) { 'PASSED' } else { "FAILED: $($failures -join '; ')" }
    Write-Host "$name -> $($results[$name])"
}

Clear-Switches
Stop-FeedServer
Write-Host ''
$results.GetEnumerator() | ForEach-Object { Write-Host ("{0,-18} {1}" -f $_.Key, $_.Value) }
if (@($results.Values | Where-Object { $_ -ne 'PASSED' }).Count -gt 0) { exit 1 }
Write-Host 'ALL PASSED'
