# Ham dung chung cho cac script. Khong chay truc tiep file nay.
$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

# Uu tien Maven Wrapper cua du an (khong can cai Maven).
$Mvn = Join-Path $RepoRoot 'mvnw.cmd'

function Invoke-Mvn {
    param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $MvnArgs)
    & $Mvn @MvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven that bai (exit $LASTEXITCODE): mvnw $($MvnArgs -join ' ')" }
}

function Find-MySqlClient {
    $command = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = Get-ChildItem 'C:\Program Files\MySQL\MySQL Server*\bin\mysql.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending
    if ($candidates) { return $candidates[0].FullName }
    throw 'Khong tim thay mysql.exe. Cai MySQL Server 8 hoac them thu muc bin cua MySQL vao PATH.'
}
