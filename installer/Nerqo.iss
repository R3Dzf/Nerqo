; Nerqo per-user Windows installer: native WPF app and embedded .NET transfer service.
; No Python, shell or browser dependencies installed or launched.
#define MyAppName "Nerqo"
#define MyAppVersion "1.0.10"
#define MyAppPublisher "R3Dzf"
[Setup]
AppId={{B36DB0B6-9931-4E92-8EDA-60C5E75F91C1}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppVerName={#MyAppName} {#MyAppVersion}
DefaultDirName={localappdata}\Programs\Nerqo
DefaultGroupName=Nerqo
PrivilegesRequired=lowest
OutputDir={#SourcePath}..\dist\Installer
OutputBaseFilename=Nerqo-Windows-Setup
WizardStyle=modern
SetupIconFile={#SourcePath}..\desktop\Nerqo.Desktop\nerqo.ico
; The .NET single-file payload is already compressed per assembly.
; Preserve those bytes for NRQDEL01 instead of compressing the complete installer stream again.
Compression=none
SolidCompression=no
UsePreviousAppDir=yes
UsePreviousTasks=yes
DisableDirPage=yes
UninstallDisplayName=Nerqo
CloseApplications=force
RestartApplications=no
UninstallDisplayIcon={app}\Nerqo.exe
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
DisableProgramGroupPage=yes
[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
[Tasks]
Name: "desktopicon"; Description: "Create desktop shortcut"; GroupDescription: "Additional options:"; Flags: unchecked
[Files]
Source: "{#SourcePath}..\dist\Windows\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{userprograms}\Nerqo"; Filename: "{app}\Nerqo.exe"
Name: "{userdesktop}\Nerqo"; Filename: "{app}\Nerqo.exe"; Tasks: desktopicon
[Run]
Filename: "{app}\Nerqo.exe"; Description: "Launch Nerqo"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  CacheDir, CachedSetup, SourceSetup: String;
begin
  if CurStep = ssPostInstall then
  begin
    { Retain the exact installer as a verified-by-hash base for the next update,
      including when this installation was started from a manual download. }
    CacheDir := ExpandConstant('{localappdata}\Nerqo\Updates');
    CachedSetup := CacheDir + '\Nerqo-Windows-Setup-{#MyAppVersion}.exe';
    SourceSetup := ExpandConstant('{srcexe}');
    if ForceDirectories(CacheDir) and (CompareText(SourceSetup, CachedSetup) <> 0) then
      if not FileCopy(SourceSetup, CachedSetup, False) then
        Log('Could not cache this installer; future updates can use the full-download fallback.');
  end;
end;
