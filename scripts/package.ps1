# Dong goi ung dung bang jpackage (co san trong JDK 25+): tao thu muc chay doc lap,
# KHONG can cai Java tren may nguoi dung (kem san runtime Java thu gon).
#   .\scripts\package.ps1              -> dist\MailSystem\MailSystem.exe (Client) + dist\MailServer\MailServer.exe
#   .\scripts\package.ps1 -Installer   -> them bo cai .msi (can WiX Toolset 3 hoac 4 trong PATH)
param([switch] $Installer, [string] $Version = '3.0.0')
. "$PSScriptRoot\_common.ps1"

$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
if (-not $env:JAVA_HOME -or -not (Test-Path $jpackage)) {
    $command = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if (-not $command) { throw 'Khong tim thay jpackage. Dat JAVA_HOME tro toi JDK 25+.' }
    $jpackage = $command.Source
}

Invoke-Mvn -q install '-DskipTests'
$dist = Join-Path $RepoRoot 'dist'
$work = Join-Path $RepoRoot 'target\package'
Remove-Item -Recurse -Force $dist, $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dist | Out-Null

function New-AppImage([string] $Module, [string] $Name, [string] $MainClass, [string[]] $Extra) {
    $inputDir = Join-Path $work $Module
    Invoke-Mvn -q -f "$Module/pom.xml" dependency:copy-dependencies "-DoutputDirectory=$inputDir" '-DincludeScope=runtime'
    $jar = Get-ChildItem (Join-Path $RepoRoot "$Module\target") -Filter "$Module-*.jar" | Select-Object -First 1
    Copy-Item $jar.FullName $inputDir
    $arguments = @('--type', 'app-image', '--name', $Name, '--app-version', $Version, '--vendor', 'Mail System',
        '--input', $inputDir, '--main-jar', $jar.Name, '--main-class', $MainClass, '--dest', $dist,
        # Mac dinh jpackage bo cac lenh trong runtime/bin; giu lai de co keytool (tao chung chi TLS / thiet bi).
        '--jlink-options', '--strip-debug --no-man-pages --no-header-files') + $Extra
    & $jpackage @arguments
    if ($LASTEXITCODE -ne 0) { throw "jpackage $Name that bai." }
    Write-Host "Da tao: $(Join-Path $dist $Name)"
}

# Client: Launcher (khong ke thua Application) de JavaFX chay duoc tu classpath.
New-AppImage 'mail-client' 'MailSystem' 'com.mailsystem.client.Launcher' @('--java-options', '-Dfile.encoding=UTF-8')
# Server: ung dung console; config/ va data/ nam canh thu muc chay (xem MAIL_HOME trong docs).
New-AppImage 'mail-server' 'MailServer' 'com.mailsystem.server.MailServerApplication' @('--win-console',
    '--java-options', '-Dfile.encoding=UTF-8')

if ($Installer) {
    $wix = Get-Command candle.exe, wix.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $wix) { throw 'Khong tim thay WiX Toolset (candle.exe / wix.exe) trong PATH: chi tao duoc app-image.' }
    & $jpackage --type msi --name MailSystem --app-version $Version --vendor 'Mail System' `
        --app-image (Join-Path $dist 'MailSystem') --dest $dist --win-menu --win-shortcut --win-dir-chooser `
        --win-upgrade-uuid 'f1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d'
    if ($LASTEXITCODE -ne 0) { throw 'Tao bo cai .msi that bai.' }
    Write-Host "Da tao bo cai: $(Get-ChildItem $dist -Filter *.msi | Select-Object -First 1)"
}
