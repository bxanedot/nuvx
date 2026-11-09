; Inno Setup script for nuvx.
; Build: cargo build --release, then
;   iscc windows\nuvx.iss
; Output: windows\Output\nuvx-setup-<version>.exe
; The version comes from Cargo.toml by default; the release workflow stamps its own:
;   iscc /DAppVersion="0.5.0" windows\nuvx.iss
; ARM64: cargo build --release --target aarch64-pc-windows-msvc, then
;   iscc /DArm64 windows\nuvx.iss  ->  nuvx-setup-<version>-arm64.exe

#define AppName "nuvx"
#ifndef AppVersion
#define AppVersion "0.5.0"
#endif
#define AppExe "nuvx.exe"
#ifdef Arm64
  #define Arch "arm64"
  #define Suffix "-arm64"
  #define BinDir "..\target\aarch64-pc-windows-msvc\release"
#else
  #define Arch "x64compatible"
  #define Suffix ""
  #define BinDir "..\target\release"
#endif

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
; Per-user install by default (no UAC prompt); the user can pick all-users instead.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed={#Arch}
ArchitecturesInstallIn64BitMode={#Arch}
MinVersion=10.0
OutputBaseFilename=nuvx-setup-{#AppVersion}{#Suffix}
Compression=lzma2/ultra
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\assets\icon\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#BinDir}\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; nuvx:// links (jam invites). HKA is HKCU for per-user installs, HKLM for all-users.
; The installed app also re-registers itself under HKCU on launch.
Root: HKA; Subkey: "Software\Classes\nuvx"; ValueType: string; ValueName: ""; ValueData: "URL:nuvx Protocol"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\nuvx"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\nuvx\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"",0"
Root: HKA; Subkey: "Software\Classes\nuvx\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"" ""%1"""

[UninstallDelete]
; Installers downloaded by the in-app updater.
Type: filesandordirs; Name: "{userappdata}\nuvx\data\updates"

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
