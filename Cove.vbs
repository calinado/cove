Set s = CreateObject("WScript.Shell")
d = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
s.Run "conhost.exe --headless powershell.exe -NoProfile -ExecutionPolicy Bypass -File " & Chr(34) & d & "\server.ps1" & Chr(34) & " -Bg", 0, False
