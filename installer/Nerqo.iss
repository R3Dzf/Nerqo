; Nerqo per-user Windows installer: native WPF app and embedded .NET transfer service.
; No Python, shell or browser dependencies installed or launched.
#define MyAppName "Nerqo"
#define MyAppVersion "1.0.2"
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
Compression=lzma2
SolidCompression=yes
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
