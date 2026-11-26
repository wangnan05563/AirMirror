; AirMirror PC unsigned per-user installer (NSIS 3.x).
; Build with:
;   makensis /DSTAGE_DIR=<directory> /DOUTFILE=<setup.exe> /DVERSION=<version> scripts\AirMirror-PC-installer.nsi
;
; The installer is intentionally unsigned: the project has no Windows code-signing
; certificate yet. It installs the portable ZIP payload (including bundled JRE) under
; %LOCALAPPDATA%\Programs\AirMirror-PC and never requires administrator rights.

Unicode true
SetCompressor /SOLID lzma
RequestExecutionLevel user
ManifestSupportedOS win10

!include "MUI2.nsh"
!include "FileFunc.nsh"

!ifndef STAGE_DIR
  !error "STAGE_DIR is required"
!endif
!ifndef OUTFILE
  !error "OUTFILE is required"
!endif
!ifndef VERSION
  !define VERSION "0.0.0"
!endif

Name "AirMirror PC"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\AirMirror-PC"
InstallDirRegKey HKCU "Software\AirMirror\PC" "InstallDir"
BrandingText "AirMirror PC ${VERSION}（未签名）"
ShowInstDetails show
ShowUninstDetails show

!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\AirMirror-PC.bat"
!define MUI_FINISHPAGE_RUN_TEXT "启动 AirMirror PC"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

Section "AirMirror PC（必需）" SecMain
  SetOutPath "$INSTDIR"
  SetOverwrite on
  File /r "${STAGE_DIR}\*.*"

  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\AirMirror"
  CreateShortCut "$SMPROGRAMS\AirMirror\AirMirror PC.lnk" "$INSTDIR\AirMirror-PC.bat" "" "$INSTDIR\app\runtime\bin\javaw.exe" 0
  CreateShortCut "$SMPROGRAMS\AirMirror\卸载 AirMirror.lnk" "$INSTDIR\Uninstall.exe"

  WriteRegStr HKCU "Software\AirMirror\PC" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\AirMirror\PC" "Version" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC" "DisplayName" "AirMirror PC"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC" "Publisher" "wangnan05563"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC" "DisplayIcon" "$INSTDIR\app\runtime\bin\javaw.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC" "NoRepair" 1
SectionEnd

Section "Uninstall"
  Delete "$SMPROGRAMS\AirMirror\AirMirror PC.lnk"
  Delete "$SMPROGRAMS\AirMirror\卸载 AirMirror.lnk"
  RMDir "$SMPROGRAMS\AirMirror"

  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirMirrorPC"
  DeleteRegKey HKCU "Software\AirMirror\PC"

  ; 保留 %APPDATA%\AirMirror 用户配置；需要彻底清理时由用户自行删除。
  RMDir /r "$INSTDIR"
SectionEnd
