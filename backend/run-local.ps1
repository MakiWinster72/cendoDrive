$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$mysqlPath = Join-Path $env:ProgramFiles 'MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path -LiteralPath $mysqlPath)) {
    $mysqlCommand = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($mysqlCommand) { $mysqlPath = $mysqlCommand.Source }
}
if (-not (Test-Path -LiteralPath $mysqlPath)) {
    throw 'mysql.exe was not found. Install the MySQL 8.0 client first.'
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Local MySQL Setup'
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.ClientSize = New-Object System.Drawing.Size(410, 184)

$userLabel = New-Object System.Windows.Forms.Label
$userLabel.Text = 'MySQL administrator user'
$userLabel.Location = New-Object System.Drawing.Point(18, 20)
$userLabel.AutoSize = $true
$form.Controls.Add($userLabel)

$userBox = New-Object System.Windows.Forms.TextBox
$userBox.Text = 'root'
$userBox.Location = New-Object System.Drawing.Point(135, 17)
$userBox.Size = New-Object System.Drawing.Size(250, 24)
$form.Controls.Add($userBox)

$passwordLabel = New-Object System.Windows.Forms.Label
$passwordLabel.Text = 'MySQL administrator password'
$passwordLabel.Location = New-Object System.Drawing.Point(18, 57)
$passwordLabel.AutoSize = $true
$form.Controls.Add($passwordLabel)

$passwordBox = New-Object System.Windows.Forms.TextBox
$passwordBox.Location = New-Object System.Drawing.Point(135, 54)
$passwordBox.Size = New-Object System.Drawing.Size(250, 24)
$passwordBox.UseSystemPasswordChar = $true
$form.Controls.Add($passwordBox)

$helpLabel = New-Object System.Windows.Forms.Label
$helpLabel.Text = 'Used only for local setup. The password is not sent or saved.'
$helpLabel.Location = New-Object System.Drawing.Point(18, 91)
$helpLabel.Size = New-Object System.Drawing.Size(370, 34)
$form.Controls.Add($helpLabel)

$okButton = New-Object System.Windows.Forms.Button
$okButton.Text = 'Create local database and start backend'
$okButton.Location = New-Object System.Drawing.Point(170, 137)
$okButton.Size = New-Object System.Drawing.Size(215, 30)
$okButton.DialogResult = [System.Windows.Forms.DialogResult]::OK
$form.Controls.Add($okButton)

$cancelButton = New-Object System.Windows.Forms.Button
$cancelButton.Text = 'Cancel'
$cancelButton.Location = New-Object System.Drawing.Point(88, 137)
$cancelButton.Size = New-Object System.Drawing.Size(74, 30)
$cancelButton.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
$form.Controls.Add($cancelButton)
$form.AcceptButton = $okButton
$form.CancelButton = $cancelButton

$dialogResult = $form.ShowDialog()
$adminUser = $userBox.Text.Trim()
$adminPassword = $passwordBox.Text
$passwordBox.Clear()
$form.Dispose()

if ($dialogResult -ne [System.Windows.Forms.DialogResult]::OK) {
    Write-Host 'Cancelled. No local database changes were made.'
    return
}
if ($adminUser -notmatch '^[A-Za-z0-9_.-]+$') {
    throw 'The administrator username contains unsupported characters.'
}
if ([string]::IsNullOrEmpty($adminPassword)) {
    throw 'The administrator password cannot be empty.'
}

# Use a random app-only password; never put the administrator password in SQL or command arguments.
$randomBytes = New-Object byte[] 24
$randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$randomGenerator.GetBytes($randomBytes)
$randomGenerator.Dispose()
$appPassword = -join ($randomBytes | ForEach-Object { $_.ToString('x2') })
[Array]::Clear($randomBytes, 0, $randomBytes.Length)

$sql = @"
CREATE DATABASE IF NOT EXISTS cendo_local_dev
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'cendo_local_app'@'localhost' IDENTIFIED BY '$appPassword';
ALTER USER 'cendo_local_app'@'localhost' IDENTIFIED BY '$appPassword';
GRANT ALL PRIVILEGES ON cendo_local_dev.* TO 'cendo_local_app'@'localhost';
"@

$mysqlProcess = New-Object System.Diagnostics.Process
$mysqlProcess.StartInfo.FileName = $mysqlPath
$mysqlProcess.StartInfo.Arguments = "--protocol=TCP --host=127.0.0.1 --port=3306 --user=$adminUser --default-character-set=utf8mb4"
$mysqlProcess.StartInfo.UseShellExecute = $false
$mysqlProcess.StartInfo.CreateNoWindow = $true
$mysqlProcess.StartInfo.RedirectStandardInput = $true
$mysqlProcess.StartInfo.RedirectStandardOutput = $true
$mysqlProcess.StartInfo.RedirectStandardError = $true
$mysqlProcess.StartInfo.EnvironmentVariables['MYSQL_PWD'] = $adminPassword

try {
    if (-not $mysqlProcess.Start()) { throw 'Could not start mysql.exe.' }
    $mysqlProcess.StandardInput.WriteLine($sql)
    $mysqlProcess.StandardInput.Close()
    $mysqlOutput = $mysqlProcess.StandardOutput.ReadToEnd()
    $mysqlError = $mysqlProcess.StandardError.ReadToEnd()
    $mysqlProcess.WaitForExit()
    if ($mysqlProcess.ExitCode -ne 0) {
        throw "Local database setup failed: $mysqlError$mysqlOutput"
    }
}
finally {
    $mysqlProcess.Dispose()
    $adminPassword = $null
    $sql = $null
}

$previousDbUrl = [Environment]::GetEnvironmentVariable('DB_URL', 'Process')
$previousDbUser = [Environment]::GetEnvironmentVariable('DB_USER', 'Process')
$previousDbPassword = [Environment]::GetEnvironmentVariable('DB_PASSWORD', 'Process')
$previousStorageRoot = [Environment]::GetEnvironmentVariable('CENDO_STORAGE_ROOT', 'Process')

try {
    $env:DB_URL = 'jdbc:mysql://localhost:3306/cendo_local_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
    $env:DB_USER = 'cendo_local_app'
    $env:DB_PASSWORD = $appPassword
    $env:CENDO_STORAGE_ROOT = '.\storage'
    Set-Location -LiteralPath $PSScriptRoot
    Write-Host 'Local database cendo_local_dev is ready. Flyway will apply project migrations on startup.'
    Write-Host 'Starting backend. Press Ctrl+C to stop.'
    & mvn spring-boot:run
}
finally {
    foreach ($entry in @(
        @{ Name = 'DB_URL'; Value = $previousDbUrl },
        @{ Name = 'DB_USER'; Value = $previousDbUser },
        @{ Name = 'DB_PASSWORD'; Value = $previousDbPassword },
        @{ Name = 'CENDO_STORAGE_ROOT'; Value = $previousStorageRoot }
    )) {
        if ($null -eq $entry.Value) {
            Remove-Item -LiteralPath "Env:$($entry.Name)" -ErrorAction SilentlyContinue
        } else {
            Set-Item -LiteralPath "Env:$($entry.Name)" -Value $entry.Value
        }
    }
    $appPassword = $null
}
