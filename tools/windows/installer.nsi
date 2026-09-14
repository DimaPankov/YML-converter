Unicode True
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!include "WinVer.nsh"
Name "YML Студия"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\YMLStudioDesktop"
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 32
VIProductVersion "2.2.2.0"
VIAddVersionKey "ProductName" "YML Студия"
VIAddVersionKey "FileDescription" "Установка YML Студии для Windows x64"
VIAddVersionKey "FileVersion" "2.2.2"
VIAddVersionKey "LegalCopyright" "YML Studio"
!define MUI_WELCOMEPAGE_TEXT "Перед установкой сохраните изменения и закройте YML Студию.$\r$\n$\r$\nПриложение устанавливается для текущего пользователя. Java включена в установщик."
!define MUI_FINISHPAGE_RUN "$INSTDIR\YMLStudio.exe"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Russian"
Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "Требуется Windows 10/11 x64."
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_OK|MB_ICONSTOP "Требуется Windows 10/11 x64."
    Abort
  ${EndIf}
FunctionEnd
Section
  SetShellVarContext current
  SetOutPath "$INSTDIR"
  File /r "${APP_SOURCE}/*"
  CreateShortcut "$DESKTOP\YML Студия.lnk" "$INSTDIR\YMLStudio.exe"
  CreateDirectory "$SMPROGRAMS\YML Студия"
  CreateShortcut "$SMPROGRAMS\YML Студия\YML Студия.lnk" "$INSTDIR\YMLStudio.exe"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateShortcut "$SMPROGRAMS\YML Студия\Удалить.lnk" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudioDesktop" "DisplayName" "YML Студия"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudioDesktop" "DisplayVersion" "2.2.2"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudioDesktop" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudioDesktop" "InstallLocation" "$INSTDIR"
SectionEnd
Section "Uninstall"
  SetShellVarContext current
  Delete "$DESKTOP\YML Студия.lnk"
  Delete "$SMPROGRAMS\YML Студия\YML Студия.lnk"
  Delete "$SMPROGRAMS\YML Студия\Удалить.lnk"
  RMDir "$SMPROGRAMS\YML Студия"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\YMLStudio.exe"
  Delete "$INSTDIR\Инструкция.txt"
  Delete "$INSTDIR\build-manifest.json"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudioDesktop"
SectionEnd
