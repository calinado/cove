# Cove - local helper for the launcher UI. Keep this file ASCII-only (Windows PowerShell 5.1 reads it as ANSI).
# It only listens on localhost, and every API call needs the per-session token baked into the page.
param([int]$Port = 47615, [switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$App = $PSScriptRoot
$Data = Join-Path $App 'data'
$Url = "http://localhost:$Port/"
$Token = [guid]::NewGuid().ToString('N')
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Open-UI {
  if ($NoBrowser) { return }
  # the autoplay switch lets menu sounds play for controller-only use (browsers otherwise wait for a key press or click)
  try { Start-Process 'msedge.exe' "--app=$Url --autoplay-policy=no-user-gesture-required" } catch { Start-Process $Url }
}

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add($Url)
$started = $false
$tries = 1; if ($NoBrowser) { $tries = 20 }          # after an update the old copy needs a moment to let go of the port
for ($i = 0; $i -lt $tries -and -not $started; $i++) {
  try { $listener.Start(); $started = $true } catch {
    if ($i + 1 -lt $tries) { Start-Sleep -Milliseconds 400; $listener = New-Object System.Net.HttpListener; $listener.Prefixes.Add($Url) }
  }
}
if (-not $started) { Open-UI; exit }   # already running: just show the window
Open-UI

# version.json says which version this is and which GitHub repo updates come from
$VerPath = Join-Path $App 'version.json'
$AppFiles = 'index.html', 'server.ps1', 'Cove.bat', 'cove.ico', 'README.txt', 'version.json'
$restart = $false
function Get-Ver { try { return [IO.File]::ReadAllText($VerPath, $Utf8) | ConvertFrom-Json } catch { return $null } }

$running = New-Object System.Collections.ArrayList   # launched games still open
$done = New-Object System.Collections.ArrayList      # finished sessions waiting for the UI to collect
$lastSeen = Get-Date
$quitAt = $null

function Send($res, [int]$code, [byte[]]$bytes, $type) {
  $res.StatusCode = $code
  $res.ContentType = $type
  $res.Headers['Cache-Control'] = 'no-store'
  $res.ContentLength64 = $bytes.Length
  $res.OutputStream.Write($bytes, 0, $bytes.Length)
  $res.Close()
}
function Json($res, $obj, [int]$code = 200) {
  Send $res $code $Utf8.GetBytes((ConvertTo-Json -InputObject $obj -Depth 6 -Compress)) 'application/json'
}
function Copy-Tree($src, $dst) {
  # merges src into dst, overwriting same-named files; never deletes
  $n = 0
  if (Test-Path -LiteralPath $src -PathType Leaf) {
    [void](New-Item -ItemType Directory -Force -Path (Split-Path $dst))
    Copy-Item -LiteralPath $src -Destination $dst -Force
    return 1
  }
  if (-not (Test-Path -LiteralPath $src)) { return 0 }
  $root = (Resolve-Path -LiteralPath $src).Path.TrimEnd('\', '/')
  foreach ($f in Get-ChildItem -LiteralPath $root -Recurse -File -Force) {
    $to = Join-Path $dst $f.FullName.Substring($root.Length + 1)
    [void](New-Item -ItemType Directory -Force -Path (Split-Path $to))
    Copy-Item -LiteralPath $f.FullName -Destination $to -Force
    $n++
  }
  return $n
}
function Walk($dir, [int]$depth, $exts, $out, [int]$rootLen) {
  # lists files under $dir; never descends into romfs (an unpacked game's assets: tens of thousands of files)
  try { $di = New-Object IO.DirectoryInfo $dir; $files = $di.GetFiles(); $dirs = $di.GetDirectories() } catch { return }
  foreach ($f in $files) {
    if (-not $exts -or $exts -contains $f.Extension.ToLower()) { [void]$out.Add(@{ p = $f.FullName.Substring($rootLen); s = $f.Length }) }
  }
  if ($depth -le 0) { return }
  foreach ($d in $dirs) {
    if ($d.Name -eq 'romfs' -or ($d.Attributes -band [IO.FileAttributes]::Hidden)) { continue }
    Walk $d.FullName ($depth - 1) $exts $out $rootLen
  }
}
function Pick($kind) {
  if ($env:COVE_PICK) { return $env:COVE_PICK }   # test hook
  Add-Type -AssemblyName System.Windows.Forms
  $owner = New-Object System.Windows.Forms.Form -Property @{ TopMost = $true; ShowInTaskbar = $false; Opacity = 0 }
  $owner.Show()
  try {
    if ($kind -eq 'folder') {
      $d = New-Object System.Windows.Forms.FolderBrowserDialog
      $d.Description = 'Choose a folder'
      if ($d.ShowDialog($owner) -eq 'OK') { return $d.SelectedPath }
    } else {
      $d = New-Object System.Windows.Forms.OpenFileDialog
      if ($kind -eq 'exe') { $d.Filter = 'Programs (*.exe)|*.exe|All files|*.*' }
      if ($kind -eq 'image') { $d.Filter = 'Images|*.png;*.jpg;*.jpeg;*.webp;*.gif' }
      if ($d.ShowDialog($owner) -eq 'OK') { return $d.FileName }
    }
  } finally { $owner.Close() }
  return ''
}
function Sweep {
  $exited = $false
  foreach ($r in @($running)) {
    if ($r.p.HasExited) {
      [void]$done.Add(@{ id = $r.id; s = [int]((Get-Date) - $r.t).TotalSeconds })
      $running.Remove($r)
      $exited = $true
    }
  }
  if ($exited -and $running.Count -eq 0 -and $script:presence) {   # last game closed: clear the Discord status
    try { Discord-Set $script:dclient $null } catch {}
    $script:presence = $false
  }
}

# Discord Rich Presence over Discord's local pipe. Frames are: int32 opcode, int32 length, JSON.
$script:dpipe = $null; $script:dclient = ''; $script:presence = $false
function Discord-Send([int]$op, $obj) {
  $json = $Utf8.GetBytes((ConvertTo-Json -InputObject $obj -Depth 8 -Compress))
  $buf = New-Object byte[] (8 + $json.Length)
  [BitConverter]::GetBytes([int]$op).CopyTo($buf, 0)
  [BitConverter]::GetBytes([int]$json.Length).CopyTo($buf, 4)
  $json.CopyTo($buf, 8)
  $script:dpipe.Write($buf, 0, $buf.Length)
  $script:dpipe.Flush()
  $in = New-Object byte[] 16384
  $t = $script:dpipe.ReadAsync($in, 0, $in.Length)
  if ($t.Wait(3000) -and $t.Result -gt 8) { return $Utf8.GetString($in, 8, $t.Result - 8) }
  return ''
}
function Discord-Set($clientId, $activity) {
  if ($script:dpipe -and ($script:dclient -ne $clientId -or -not $script:dpipe.IsConnected)) { $script:dpipe.Dispose(); $script:dpipe = $null }
  if (-not $script:dpipe) {
    foreach ($i in 0..9) {
      $p = New-Object System.IO.Pipes.NamedPipeClientStream('.', "discord-ipc-$i", [System.IO.Pipes.PipeDirection]::InOut, [System.IO.Pipes.PipeOptions]::Asynchronous)
      try { $p.Connect(300); $script:dpipe = $p; break } catch { $p.Dispose() }
    }
    if (-not $script:dpipe) { throw 'Discord is not running on this PC' }
    $script:dclient = $clientId
    $r = Discord-Send 0 @{ v = 1; client_id = [string]$clientId }
    if ($r -notmatch 'READY') { $script:dpipe.Dispose(); $script:dpipe = $null; throw "Discord did not accept that Application ID. $r" }
  }
  $r = Discord-Send 1 @{ cmd = 'SET_ACTIVITY'; nonce = [guid]::NewGuid().ToString(); args = @{ pid = $PID; activity = $activity } }
  if ($r -match '"evt"\s*:\s*"ERROR"') { throw "Discord rejected the status. $r" }
}

function Handle($ctx) {
  $req = $ctx.Request; $res = $ctx.Response
  $path = $req.Url.AbsolutePath
  if ($req.UserHostName -ne "localhost:$Port") { Json $res @{ error = 'bad host' } 403; return }
  if ($path -eq '/') {
    $html = [IO.File]::ReadAllText((Join-Path $App 'index.html'), $Utf8).Replace('__TOKEN__', $Token)
    Send $res 200 $Utf8.GetBytes($html) 'text/html; charset=utf-8'; return
  }
  if ($path -eq '/cove.ico') { Send $res 200 ([IO.File]::ReadAllBytes((Join-Path $App 'cove.ico'))) 'image/x-icon'; return }
  if ($req.Headers['X-Token'] -ne $Token -and $req.QueryString['t'] -ne $Token) { Json $res @{ error = 'bad token' } 403; return }

  if ($path -eq '/file') {   # images only: custom artwork and wallpapers
    $p = $req.QueryString['p']
    $types = @{ '.png' = 'image/png'; '.jpg' = 'image/jpeg'; '.jpeg' = 'image/jpeg'; '.webp' = 'image/webp'; '.gif' = 'image/gif' }
    $t = $types[[IO.Path]::GetExtension($p).ToLower()]
    if ($t -and (Test-Path -LiteralPath $p -PathType Leaf)) { Send $res 200 ([IO.File]::ReadAllBytes($p)) $t } else { Json $res @{ error = 'not found' } 404 }
    return
  }

  $body = (New-Object IO.StreamReader($req.InputStream, $Utf8)).ReadToEnd()
  $statePath = Join-Path $Data 'state.json'
  if ($path -eq '/api/state') {
    if ($req.HttpMethod -eq 'POST') {
      [void](New-Item -ItemType Directory -Force -Path $Data)
      [IO.File]::WriteAllText("$statePath.tmp", $body, $Utf8)
      Move-Item -LiteralPath "$statePath.tmp" -Destination $statePath -Force
      Json $res @{ ok = $true }
    } else {
      $f = $statePath
      if (-not (Test-Path -LiteralPath $f)) { $f = Join-Path $Data 'preset.json' }
      $txt = '{}'
      if (Test-Path -LiteralPath $f) { $txt = [IO.File]::ReadAllText($f, $Utf8) }
      Send $res 200 $Utf8.GetBytes($txt) 'application/json'
    }
    return
  }
  $j = $null
  if ($body) { $j = $body | ConvertFrom-Json }

  switch ($path) {
    '/api/info' {
      $ver = Get-Ver
      Json $res @{ v = 5; version = [string]$ver.version; repo = [string]$ver.repo; sep = [string][IO.Path]::DirectorySeparatorChar; app = $App; appdata = $env:APPDATA; local = $env:LOCALAPPDATA
        docs = [Environment]::GetFolderPath('MyDocuments'); desktop = [Environment]::GetFolderPath('Desktop') }
    }
    '/api/ping' { Sweep; $d = @($done); $done.Clear(); Json $res @{ done = $d; running = $running.Count; ids = @($running | ForEach-Object { $_.id }) } }
    '/api/quit' { $script:quitAt = (Get-Date).AddSeconds(8); Json $res @{ ok = $true } }
    '/api/exists' { Json $res @{ r = @($j.paths | ForEach-Object { [bool]($_ -and (Test-Path -LiteralPath $_)) }) } }
    '/api/mkdir' { foreach ($p in $j.paths) { [void](New-Item -ItemType Directory -Force -Path $p) }; Json $res @{ ok = $true } }
    '/api/pick' { Json $res @{ path = [string](Pick $j.kind) } }
    '/api/ls' {
      $root = $j.path; $out = New-Object System.Collections.ArrayList
      if ($root -and (Test-Path -LiteralPath $root)) {
        $root = (Resolve-Path -LiteralPath $root).Path.TrimEnd('\', '/')
        Walk $root ([int]$j.depth) $j.exts $out ($root.Length + 1)
      }
      Json $res @{ root = $root; items = $out.ToArray() }
    }
    '/api/browse' {   # folder browser for the UI: drives when path is empty
      $p = [string]$j.path
      if (-not $p) {
        Json $res @{ path = ''; parent = ''; files = @(); dirs = @([IO.DriveInfo]::GetDrives() | Where-Object { $_.IsReady } | ForEach-Object { $_.Name }) }
      } else {
        $di = New-Object IO.DirectoryInfo $p
        $parent = ''
        if ($di.Parent) { $parent = $di.Parent.FullName }
        $dirs = @($di.GetDirectories() | Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::Hidden) } | ForEach-Object { $_.Name })
        $files = @($di.GetFiles() | Where-Object { $j.exts -contains $_.Extension.ToLower() } | ForEach-Object { $_.Name })
        Json $res @{ path = $di.FullName; parent = $parent; dirs = $dirs; files = $files }
      }
    }
    '/api/trash' {   # Recycle Bin only, and only inside the library or a games folder listed in state.json
      $st = [IO.File]::ReadAllText($statePath, $Utf8) | ConvertFrom-Json
      $p = [IO.Path]::GetFullPath([string]$j.path)
      $ok = $false
      foreach ($r in (@($st.roots) + @($st.library))) {
        if ($r -and $p.StartsWith([IO.Path]::GetFullPath($r).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { $ok = $true }
      }
      if (-not $ok) { throw 'That is not inside one of your game folders' }
      Add-Type -AssemblyName Microsoft.VisualBasic
      if (Test-Path -LiteralPath $p -PathType Container) { [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($p, 'OnlyErrorDialogs', 'SendToRecycleBin') }
      else { [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($p, 'OnlyErrorDialogs', 'SendToRecycleBin') }
      Json $res @{ ok = $true }
    }
    '/api/move' {   # never overwrites: a file that already exists at the destination is skipped
      $moved = 0; $skipped = 0
      foreach ($m in $j.moves) {
        if ((Test-Path -LiteralPath $m.to) -or -not (Test-Path -LiteralPath $m.from)) { $skipped++; continue }
        [void](New-Item -ItemType Directory -Force -Path (Split-Path $m.to))
        Move-Item -LiteralPath $m.from -Destination $m.to
        $moved++
      }
      Json $res @{ moved = $moved; skipped = $skipped }
    }
    '/api/copy' {   # ponytail: runs inline and blocks the UI while copying; move to a job if big save folders get slow
      $n = 0
      if ($j.items) { foreach ($i in $j.items) { $n += Copy-Tree (Join-Path $j.from $i) (Join-Path $j.to $i) } }
      else { $n = Copy-Tree $j.from $j.to }
      Json $res @{ files = $n }
    }
    '/api/launch' {
      $a = @{ FilePath = $j.exe; PassThru = $true }
      if ($j.args) { $a.ArgumentList = $j.args }
      if ($j.cwd) { $a.WorkingDirectory = $j.cwd }
      $p = Start-Process @a
      if ($p) { [void]$running.Add(@{ id = $j.id; p = $p; t = Get-Date }) }
      Json $res @{ ok = $true }
    }
    '/api/discord' {
      Discord-Set ([string]$j.clientId) $j.activity
      $script:presence = [bool]$j.activity
      Json $res @{ ok = $true }
    }
    '/api/update' {   # only ever downloads the fixed file list from this copy's own repo, and keeps the old files
      $v = Get-Ver
      if (-not $v -or $v.repo -notmatch '^[\w.-]+/[\w.-]+$') { throw 'This copy has no update address' }
      [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
      $base = "https://raw.githubusercontent.com/$($v.repo)/main/"
      $tmp = Join-Path ([IO.Path]::GetTempPath()) ('Cove-update-' + $Token.Substring(0, 8))
      [void](New-Item -ItemType Directory -Force -Path $tmp)
      $wc = New-Object System.Net.WebClient
      foreach ($f in $AppFiles) { $wc.DownloadFile($base + $f, (Join-Path $tmp $f)) }
      $new = [IO.File]::ReadAllText((Join-Path $tmp 'version.json'), $Utf8) | ConvertFrom-Json
      if ($new.repo -ne $v.repo) { throw 'The update points to a different address, so it was not installed' }
      $page = [IO.File]::ReadAllText((Join-Path $tmp 'index.html'), $Utf8)
      if (-not $page.Contains('__TOKEN__') -or (Get-Item -LiteralPath (Join-Path $tmp 'server.ps1')).Length -lt 2000) { throw 'The download looks incomplete, so nothing was changed' }
      $bak = Join-Path $Data ('backup-' + $v.version)
      [void](New-Item -ItemType Directory -Force -Path $bak)
      foreach ($f in $AppFiles) { if (Test-Path -LiteralPath (Join-Path $App $f)) { Copy-Item -LiteralPath (Join-Path $App $f) -Destination $bak -Force } }
      foreach ($f in $AppFiles) { Copy-Item -LiteralPath (Join-Path $tmp $f) -Destination $App -Force }
      Json $res @{ ok = $true; version = [string]$new.version }
      $script:restart = $true
    }
    '/api/open' { Start-Process 'explorer.exe' ('"' + $j.path + '"'); Json $res @{ ok = $true } }
    '/api/shortcut' {
      $sh = New-Object -ComObject WScript.Shell
      $name = ($j.name -replace '[\\/:*?"<>|]', '_')
      $lnk = $sh.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) "$name.lnk"))
      if ($j.exe) {   # straight to a game: emulator + arguments
        $lnk.TargetPath = $j.exe; $lnk.Arguments = [string]$j.args; $lnk.WorkingDirectory = [string]$j.cwd
      } else {        # the launcher itself
        $lnk.TargetPath = 'powershell.exe'
        $lnk.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + (Join-Path $App 'server.ps1') + '"'
        $lnk.WorkingDirectory = $App; $lnk.IconLocation = (Join-Path $App 'cove.ico'); $lnk.WindowStyle = 7
      }
      $lnk.Save()
      Json $res @{ ok = $true }
    }
    '/api/pack' {   # zip the app (no games, no personal paths) plus the look/custom-system preset
      $tmp = Join-Path ([IO.Path]::GetTempPath()) ('Cove-' + $Token.Substring(0, 8))
      $out = Join-Path $tmp 'Cove'
      [void](New-Item -ItemType Directory -Force -Path (Join-Path $out 'data'))
      foreach ($f in $AppFiles) { Copy-Item -LiteralPath (Join-Path $App $f) -Destination $out -Force }
      [IO.File]::WriteAllText((Join-Path $out 'data/preset.json'), [string]$j.preset, $Utf8)
      $zip = Join-Path $App 'Cove-share.zip'
      Compress-Archive -Path $out -DestinationPath $zip -Force
      Json $res @{ path = $zip }
    }
    default { Json $res @{ error = 'unknown' } 404 }
  }
}

while ($listener.IsListening) {
  $task = $listener.GetContextAsync()
  while (-not $task.Wait(1000)) {
    Sweep
    if ($quitAt -and (Get-Date) -gt $quitAt) { $listener.Stop(); exit }
    if ($running.Count -eq 0 -and ((Get-Date) - $lastSeen).TotalMinutes -gt 10) { $listener.Stop(); exit }
  }
  $ctx = $task.Result
  $lastSeen = Get-Date
  if ($ctx.Request.Url.AbsolutePath -ne '/api/quit') { $quitAt = $null }
  try { Handle $ctx } catch {
    try { Json $ctx.Response @{ error = $_.Exception.Message } 500 } catch {}
  }
  if ($restart) {   # an update was installed: hand over to the new helper
    $listener.Stop(); $listener.Close()
    Start-Process 'powershell.exe' ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + (Join-Path $App 'server.ps1') + '" -NoBrowser') -WindowStyle Hidden
    exit
  }
}
