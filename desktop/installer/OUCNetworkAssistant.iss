#define AppVersion "0.3.6"
#define AppName "OUNet"

[Setup]
AppId={{A6ED0C7B-B925-4FB1-84B1-F90D81234E73}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=OUNet Project
AppPublisherURL=https://github.com/JieShenAI/OUNet
AppSupportURL=mailto:shencv@163.com
AppUpdatesURL=https://github.com/JieShenAI/OUNet/releases
AppComments=个人校园网络工具，非中国海洋大学官方软件
DefaultDirName={localappdata}\Programs\OUCNetworkAssistant
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir=..\dist
OutputBaseFilename=OUNet-{#AppVersion}-Setup-x64
SetupIconFile=..\assets\app.ico
UninstallDisplayIcon={app}\OUNet.exe
WizardStyle=modern
WizardSizePercent=112
DisableWelcomePage=no
InfoBeforeFile=INTRO.txt
Compression=lzma2/normal
SolidCompression=yes
CloseApplications=no
RestartApplications=no
AppMutex=Local\OUCNetworkAssistant
ShowLanguageDialog=no
UsePreviousTasks=no

[Languages]
Name: "zhcn"; MessagesFile: "compiler:Default.isl,zh_CN.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; Flags: unchecked

[Files]
Source: "..\dist\OUNet\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\OUNet.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\OUNet.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "OUCNetworkAssistant"; Flags: uninsdeletevalue

[Run]
Filename: "{app}\OUNet.exe"; Description: "启动OUNet并完成设置"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  StartupCommand, OldExe, NewExe: String;
begin
  if CurStep = ssPostInstall then
  begin
    { Preserve the existing opt-in when the executable is renamed. The data
      directory, AppId, mutex and startup value name stay upgrade compatible. }
    OldExe := ExpandConstant('{app}\OUCNetworkAssistant.exe');
    NewExe := ExpandConstant('{app}\OUNet.exe');
    if Pos(' ', OldExe) > 0 then OldExe := '"' + OldExe + '"';
    if Pos(' ', NewExe) > 0 then NewExe := '"' + NewExe + '"';
    if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run',
      'OUCNetworkAssistant', StartupCommand) then
    begin
      if CompareText(StartupCommand, OldExe + ' --background') = 0 then
        RegWriteStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run',
          'OUCNetworkAssistant', NewExe + ' --background');
    end;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if (CurUninstallStep = usPostUninstall) and (not UninstallSilent) then
  begin
    if MsgBox('是否同时清除本机保存的校园网账号、设置、会话快照和恢复明细？' + #13#10 +
      '连续请求失败的暂停状态将保留，重新安装不会绕过暂停。' + #13#10 +
      '选择“否”可在重新安装时继续使用原配置。', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
    begin
      DataDir := ExpandConstant('{localappdata}\OUCNetworkAssistant');
      DeleteFile(DataDir + '\credentials.dat');
      DeleteFile(DataDir + '\settings.json');
      DeleteFile(DataDir + '\portal-snapshot.dat');
      DeleteFile(DataDir + '\recoveries.json');
      DeleteFile(DataDir + '\logs\activity.jsonl');
      RemoveDir(DataDir + '\logs');
    end;
  end;
end;
