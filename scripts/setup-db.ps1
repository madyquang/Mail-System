# Chuan bi database cho Mail System:
#  - Chua co database mail_system: chay database/schema.sql.
#  - Da co: sao luu (mysqldump) roi chay lan luot database/migrations/*.sql (chay lai nhieu lan van an toan).
#  - Tao config/server.properties (chua mat khau MySQL, bi .gitignore) neu chua co.
#   .\scripts\setup-db.ps1                 (hoi mat khau MySQL)
#   .\scripts\setup-db.ps1 -User root -NoBackup
param(
    [string] $User = 'root',
    [string] $Password = '',
    [switch] $NoBackup
)
. "$PSScriptRoot\_common.ps1"
$mysql = Find-MySqlClient
if (-not $Password) {
    $secure = Read-Host "Mat khau MySQL cua '$User'" -AsSecureString
    $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
$env:MYSQL_PWD = $Password   # khong dua mat khau len dong lenh (tranh lo qua danh sach tien trinh)

$exists = & $mysql -u $User -N -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='mail_system'"
if ($LASTEXITCODE -ne 0) { throw 'Khong ket noi duoc MySQL. Kiem tra user/mat khau va dich vu MySQL.' }

if ($exists.Trim() -eq '0') {
    Write-Host 'Tao database moi tu database/schema.sql ...'
    Get-Content 'database\schema.sql' -Raw -Encoding UTF8 | & $mysql -u $User --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw 'Chay schema.sql that bai.' }
} else {
    if (-not $NoBackup) {
        $backupDir = Join-Path (Split-Path -Parent $RepoRoot) 'Mail-System-backups'
        New-Item -ItemType Directory -Force $backupDir | Out-Null
        $dump = Join-Path (Split-Path $mysql) 'mysqldump.exe'
        $file = Join-Path $backupDir ("mail_system_" + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.sql')
        & $dump -u $User --single-transaction --default-character-set=utf8mb4 --databases mail_system --result-file="$file"
        if ($LASTEXITCODE -ne 0) { throw 'Sao luu that bai, dung lai de an toan.' }
        Write-Host "Da sao luu: $file"
    }
    foreach ($migration in Get-ChildItem 'database\migrations\*.sql' | Sort-Object Name) {
        Write-Host "Ap dung $($migration.Name) ..."
        Get-Content $migration.FullName -Raw -Encoding UTF8 | & $mysql -u $User --default-character-set=utf8mb4
        if ($LASTEXITCODE -ne 0) { throw "Migration $($migration.Name) that bai." }
    }
}

$config = 'config\server.properties'
if (-not (Test-Path $config)) {
    "# Cau hinh rieng cua may nay (KHONG commit). Mau: server.properties.example`ndb.username=$User`ndb.password=$Password" |
        Set-Content $config -Encoding UTF8
    Write-Host "Da tao $config"
}
Remove-Item Env:MYSQL_PWD
Write-Host 'Database san sang.' -ForegroundColor Green
