COVE - one launcher for every emulator
======================================

START
  Double-click Cove.bat. The first run walks you through setup and can put a
  shortcut on your desktop. Nothing to install: it uses PowerShell and
  Microsoft Edge, which Windows 10 and 11 already include.

WHAT IT DOES
  - Finds your emulators (pick a folder, it scans it) and lets you add any
    .exe it does not know, including future forks.
  - Reads your games where they already are. Add a games folder and Cove works
    out the system for each file, including unpacked Switch games (a folder
    with exefs and romfs). Ambiguous files (.iso, .chd ...) show under Unsorted
    until you pick a system. Nothing is moved unless you use Tidy up.
  - Launches each game in the right emulator with the right arguments. Each
    emulator entry is a saved launch profile: duplicate one to keep a second
    set of arguments, set a default per system, or override per game.
  - Game options (right-click or Y): system, emulator, artwork, desktop
    shortcut, Hide, and Delete (moves the game to the Recycle Bin).
  - Imports saves and settings from one emulator into another, backing up the
    destination first (Settings > Saves).
  - Custom box art: use "Set artwork" on a game, or put <game file name>.png in
    Library\Art\<system>. Otherwise it tries libretro thumbnails online.

CONTROLS
  Arrows / D-pad   move          Enter / A      launch
  Q, E / LB, RB    switch tab    F / X          favourite
  O / Y            game options  S / Start      settings
  /                search        Esc / B        back
  In panels: up/down move between controls, left/right change a dropdown or
  slider, A presses, B goes back, LB/RB switch settings tabs. Folder and file
  choosers are built in, so a controller works there too. Typing a name or
  search still needs a keyboard.

SHARE WITH FRIENDS
  Settings > Share > Export share pack makes Cove-share.zip with your theme and
  custom systems, without your games or paths. They unzip and run Cove.bat.
  Windows may show a SmartScreen prompt for a downloaded .bat: More info > Run.

EDITING
  Everything is two readable files: index.html (look, systems, emulator
  definitions) and server.ps1 (local helper). Your settings are in
  data\state.json.

CALINADO (c) 2026
