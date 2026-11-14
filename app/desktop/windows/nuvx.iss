#define AppName "nuvx"
#ifndef AppVersion
#define AppVersion "0.9.0"
#endif
#define AppExe "nuvx.exe"

[Setup]
AppId={{469DB82D-3113-47EA-812C-36393395493F}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=bxane
AppPublisherURL=https://github.com/bxanedot/nuvx
AppSupportURL=https://github.com/bxanedot/nuvx/issues
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible arm64
ArchitecturesInstallIn64BitMode=x64compatible arm64
MinVersion=10.0
OutputBaseFilename=nuvx-setup-{#AppVersion}
Compression=lzma2/ultra
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\assets\icon\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "..\target\x86_64-pc-windows-msvc\release\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion; Check: not IsArm64
Source: "..\target\aarch64-pc-windows-msvc\release\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion; Check: IsArm64
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
Root: HKA; Subkey: "Software\Classes\nuvx"; ValueType: string; ValueName: ""; ValueData: "URL:nuvx Protocol"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\nuvx"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\nuvx\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"",0"
Root: HKA; Subkey: "Software\Classes\nuvx\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"" ""%1"""

[UninstallDelete]
Type: filesandordirs; Name: "{userappdata}\nuvx\data\updates"

[InstallDelete]
Type: files; Name: "{app}\portable.txt"

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
