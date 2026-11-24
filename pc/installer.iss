#ifndef AppVersion
  #define AppVersion "1.9.2"
#endif
#ifndef SourceDir
  #define SourceDir "release_pkg"
#endif
#ifndef OutputDir
  #define OutputDir ".."
#endif

[Setup]
AppId={{8E7A2C1D-4B3F-4E91-9A2D-7F5C1E3B9A01}
AppName=Echolicense VPN
AppVersion={#AppVersion}
AppVerName=Echolicense VPN v{#AppVersion}
AppPublisher=Echolicense
AppPublisherURL=https://app.echolicense.ai
AppSupportURL=https://t.me/echopress_bot
DefaultDirName={autopf}\Echolicense VPN
DefaultGroupName=Echolicense VPN
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=Echolicense-Windows-Setup-v{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\EcholicenseVPN.exe
CloseApplications=force
RestartApplications=no
#ifdef IconFile
SetupIconFile={#IconFile}
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Echolicense VPN"; Filename: "{app}\EcholicenseVPN.exe"
Name: "{autodesktop}\Echolicense VPN"; Filename: "{app}\EcholicenseVPN.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\EcholicenseVPN.exe"; Description: "Launch Echolicense VPN"; Flags: nowait postinstall shellexec

[Code]
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec('taskkill.exe', '/F /IM EcholicenseVPN.exe /T', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/F /IM sing-box.exe /T', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;
