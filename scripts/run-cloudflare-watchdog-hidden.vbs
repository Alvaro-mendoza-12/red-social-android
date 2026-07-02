Option Explicit

Dim shell, fileSystem, scriptsDirectory, watchdogPath, command
Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")

scriptsDirectory = fileSystem.GetParentFolderName(WScript.ScriptFullName)
watchdogPath = fileSystem.BuildPath(scriptsDirectory, "watch-cloudflare-tunnel.ps1")
command = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File """ & watchdogPath & """"

' Window style 0 keeps the watchdog completely invisible. Wait for completion
' so Task Scheduler records the real exit code and avoids overlapping runs.
WScript.Quit shell.Run(command, 0, True)
