Unicode True
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!include "WinVer.nsh"

Name "YML Студия"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\YMLStudio"
InstallDirRegKey HKCU "Software\YMLStudio" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 32
BrandingText "YML Студия • 1.0.0"
Icon "${APP_ICON}"
UninstallIcon "${APP_ICON}"
VIProductVersion "1.0.0.0"
VIAddVersionKey "ProductName" "YML Студия"
VIAddVersionKey "FileDescription" "Установка YML Студии для Windows x64"
VIAddVersionKey "FileVersion" "1.0.0"
VIAddVersionKey "ProductVersion" "1.0.0"
VIAddVersionKey "LegalCopyright" "YML Студия"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "Установка YML Студии"
!define MUI_WELCOMEPAGE_TEXT "Конструктор форм и прайс-листов для Портала поставщиков Москвы.$\r$\n$\r$\nПрограмма установится для текущего пользователя. Ярлыки появятся на рабочем столе и в меню «Пуск».$\r$\n$\r$\nPython включён в установщик. При запуске используется окно Microsoft Edge или Google Chrome; если они не найдены — браузер по умолчанию.$\r$\n$\r$\nПеред обновлением сохраните данные и закройте программу."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Открыть YML Студию"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchApp
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!define MUI_UNCONFIRMPAGE_TEXT_TOP "Программа будет удалена. Формы, товары и фотографии сохранятся в папке пользователя:$\r$\n$LOCALAPPDATA\YMLStudio\data$\r$\n$\r$\nПеред удалением сохраните изменения в открытом окне программы."
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Russian"

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "Для этой версии требуется 64-разрядная Windows 10 или 11."
    Abort
  ${EndIf}
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_OK|MB_ICONSTOP "Для этой версии требуется Windows 10 или 11."
    Abort
  ${EndIf}
  SetShellVarContext current
FunctionEnd

Section "YML Студия" SEC_MAIN
  SetShellVarContext current
  IfFileExists "$INSTDIR\runtime\pythonw.exe" 0 install_files
    ClearErrors
    ExecWait '"$INSTDIR\runtime\pythonw.exe" "$INSTDIR\desktop.py" --stop' $0
    ${If} ${Errors}
    ${OrIf} $0 != 0
      MessageBox MB_OK|MB_ICONSTOP "Не удалось завершить работающую программу. Закройте YML Студию и повторите установку."
      Abort
    ${EndIf}
  install_files:
  SetOutPath "$INSTDIR"
  File /r "${APP_SOURCE}/*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\YML Студия"
  CreateShortcut "$DESKTOP\YML Студия.lnk" "$INSTDIR\runtime\pythonw.exe" '"$INSTDIR\desktop.py"' "$INSTDIR\app.ico"
  CreateShortcut "$SMPROGRAMS\YML Студия\YML Студия.lnk" "$INSTDIR\runtime\pythonw.exe" '"$INSTDIR\desktop.py"' "$INSTDIR\app.ico"
  CreateShortcut "$SMPROGRAMS\YML Студия\Инструкция.lnk" "$INSTDIR\Инструкция Windows.txt"
  CreateShortcut "$SMPROGRAMS\YML Студия\Удалить YML Студию.lnk" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\YMLStudio" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio" "DisplayName" "YML Студия"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio" "DisplayVersion" "1.0.0"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio" "DisplayIcon" "$INSTDIR\app.ico"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio" "NoRepair" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio" "EstimatedSize" ${APP_SIZE_KB}
SectionEnd

Function LaunchApp
  ExecShell "open" "$DESKTOP\YML Студия.lnk"
FunctionEnd

Section "Uninstall"
  SetShellVarContext current
  IfFileExists "$INSTDIR\runtime\pythonw.exe" 0 remove_files
    ClearErrors
    ExecWait '"$INSTDIR\runtime\pythonw.exe" "$INSTDIR\desktop.py" --stop' $0
    ${If} ${Errors}
    ${OrIf} $0 != 0
      MessageBox MB_OK|MB_ICONSTOP "Не удалось завершить программу. Закройте YML Студию и повторите удаление."
      Abort
    ${EndIf}
  remove_files:
  Delete "$DESKTOP\YML Студия.lnk"
  Delete "$SMPROGRAMS\YML Студия\YML Студия.lnk"
  Delete "$SMPROGRAMS\YML Студия\Инструкция.lnk"
  Delete "$SMPROGRAMS\YML Студия\Удалить YML Студию.lnk"
  RMDir "$SMPROGRAMS\YML Студия"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\static"
  RMDir /r "$INSTDIR\__pycache__"
  Delete "$INSTDIR\desktop.py"
  Delete "$INSTDIR\server.py"
  Delete "$INSTDIR\catalog.py"
  Delete "$INSTDIR\app.ico"
  Delete "$INSTDIR\README.md"
  Delete "$INSTDIR\Инструкция Windows.txt"
  Delete "$INSTDIR\build-manifest.json"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YMLStudio"
  DeleteRegKey HKCU "Software\YMLStudio"
  ; Deliberately preserve $LOCALAPPDATA\YMLStudio, including data and backups.
SectionEnd
