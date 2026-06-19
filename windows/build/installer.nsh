; ── customInit ──────────────────────────────────────────
; Runs inside .onInit, after multiUser init.
; Launches the HTA installer UI then switches NSIS to
; silent mode so no default UI (SpiderBanner, etc.) shows.
!macro customInit
  ; Write initial progress marker
  FileOpen $R0 "$TEMP\automint-progress.txt" w
  FileWrite $R0 "starting"
  FileClose $R0

  ; Extract the HTA and icon to %TEMP% (not $PLUGINSDIR — survives NSIS exit)
  SetOutPath $TEMP
  File "${BUILD_RESOURCES_DIR}\installer.hta"
  File "${BUILD_RESOURCES_DIR}\icon.ico"

  ; Check if mshta.exe exists (may be blocked in some corporate environments)
  IfFileExists "$SYSDIR\mshta.exe" 0 custom_init_done

  ; Create a shortcut to launch HTA — taskbar uses the shortcut's icon
  CreateShortCut "$TEMP\automint-installer.lnk" "$SYSDIR\mshta.exe" \
    '"$TEMP\installer.hta"' "$TEMP\icon.ico" 0
  ExecShell "" "$TEMP\automint-installer.lnk"

  ; Wait for HTA window to appear, then strip title bar via Win32 API
  StrCpy $R3 0
  find_hta_loop:
    IntOp $R3 $R3 + 1
    IntCmp $R3 20 strip_done  ; give up after ~4s
    Sleep 200
    System::Call 'user32::FindWindowW(i 0, w "Automint Installer") i .R1'
    IntCmp $R1 0 find_hta_loop

  ; Strip WS_CAPTION, WS_SYSMENU, WS_THICKFRAME, WS_MINIMIZEBOX, WS_MAXIMIZEBOX
  System::Call 'user32::GetWindowLongW(i R1, i -16) i .R2'
  IntOp $R2 $R2 & 0xFF30FFFF
  System::Call 'user32::SetWindowLongW(i R1, i -16, i R2)'

  ; Get screen size for centering
  System::Call 'user32::GetSystemMetrics(i 0) i .R3'
  System::Call 'user32::GetSystemMetrics(i 1) i .R4'
  IntOp $R3 $R3 - 520
  IntOp $R3 $R3 / 2
  IntOp $R4 $R4 - 720
  IntOp $R4 $R4 / 2

  ; Reposition + force frame redraw (SWP_FRAMECHANGED = 0x0020)
  System::Call 'user32::SetWindowPos(i R1, i 0, i R3, i R4, i 520, i 720, i 0x0020)'


  strip_done:

  ; Switch to silent mode — suppresses SpiderBanner and all
  ; native NSIS UI while the install runs in the background.
  SetSilent silent

  custom_init_done:
!macroend

; ── customInstall ───────────────────────────────────────
; Runs at the end of the install section, after files are
; extracted, registry is written, and shortcuts are created.
; Writes the completion marker so the HTA can show LAUNCH.
!macro customInstall
  ; Write completion marker with the install directory path
  FileOpen $R0 "$TEMP\automint-progress.txt" w
  FileWrite $R0 "complete"
  FileWrite $R0 "$\n"
  FileWrite $R0 "$INSTDIR"
  FileClose $R0
!macroend
