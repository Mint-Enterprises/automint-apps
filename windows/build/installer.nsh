!macro customInit
  FileOpen $R0 "$TEMP\automint-progress.txt" w
  FileWrite $R0 "starting$\r$\n${VERSION}"
  FileClose $R0

  SetOutPath $TEMP
  File "${BUILD_RESOURCES_DIR}\installer.hta"
  File "${BUILD_RESOURCES_DIR}\icon.ico"

  IfFileExists "$SYSDIR\mshta.exe" 0 custom_init_done

  CreateShortCut "$TEMP\automint-installer.lnk" "$SYSDIR\mshta.exe" \
    '"$TEMP\installer.hta"' "$TEMP\icon.ico" 0
  ExecShell "" "$TEMP\automint-installer.lnk"

  StrCpy $R3 0
  find_hta_loop:
    IntOp $R3 $R3 + 1
    IntCmp $R3 20 strip_done  ; give up after ~4s
    Sleep 200
    System::Call 'user32::FindWindowW(i 0, w "Automint Installer") i .R1'
    IntCmp $R1 0 find_hta_loop

  System::Call 'user32::GetWindowLongW(i R1, i -16) i .R2'
  IntOp $R2 $R2 & 0xFF30FFFF
  System::Call 'user32::SetWindowLongW(i R1, i -16, i R2)'

  System::Call 'user32::GetSystemMetrics(i 0) i .R3'
  System::Call 'user32::GetSystemMetrics(i 1) i .R4'
  IntOp $R3 $R3 - 520
  IntOp $R3 $R3 / 2
  IntOp $R4 $R4 - 720
  IntOp $R4 $R4 / 2

  System::Call 'user32::SetWindowPos(i R1, i 0, i R3, i R4, i 520, i 720, i 0x0020)'


  strip_done:

  SetSilent silent

  custom_init_done:
!macroend

!macro customInstall
  FileOpen $R0 "$TEMP\automint-progress.txt" w
  FileWrite $R0 "complete"
  FileWrite $R0 "$\n"
  FileWrite $R0 "$INSTDIR"
  FileWrite $R0 "$\n"
  FileWrite $R0 "${VERSION}"
  FileClose $R0
!macroend
