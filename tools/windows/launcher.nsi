Unicode True
!include "FileFunc.nsh"
Name "YML Студия"
OutFile "${OUTPUT_FILE}"
RequestExecutionLevel user
SilentInstall silent
AutoCloseWindow true
Section
  SetOutPath "$EXEDIR"
  ${GetParameters} $0
  ClearErrors
  Exec '"$EXEDIR\runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 -Dcompose.application.configure.swing.globals=true "-Dcompose.application.resources.dir=$EXEDIR\app\resources" -cp "$EXEDIR\app\*" ru.ymlstudio.MainKt $0'
  IfErrors 0 done
    MessageBox MB_OK|MB_ICONSTOP "Не удалось запустить Java. Повторите установку YML Студии."
  done:
SectionEnd
