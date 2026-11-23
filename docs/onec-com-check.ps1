# Диагностика COM-соединения с 1С (V83.COMConnector).
# Запускать на машине, где установлена платформа 1С.

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$server = if ($env:ONEC_SERVER) { $env:ONEC_SERVER } else { 'app1' }
$database = if ($env:ONEC_DATABASE) { $env:ONEC_DATABASE } else { 'albatros' }

Write-Host "Сервер: $server  База: $database"
Write-Host ""

# --- Шаг 1. Зарегистрирован ли COM-коннектор ---
$registered = $false
try {
    $connector = New-Object -ComObject V83.COMConnector
    $registered = $true
    Write-Host "[OK] V83.COMConnector зарегистрирован"
} catch {
    Write-Host "[FAIL] V83.COMConnector не зарегистрирован"
}

# --- Шаг 2. Ищем comcntr.dll на диске ---
Write-Host ""
Write-Host "Поиск comcntr.dll..."

$roots = @(
    'C:\Program Files\1C',
    'C:\Program Files (x86)\1C',
    'C:\Program Files\1cv8',
    'C:\Program Files (x86)\1cv8'
)

$found = @()
foreach ($root in $roots) {
    if (Test-Path $root) {
        $found += Get-ChildItem -Path $root -Filter 'comcntr.dll' -Recurse -ErrorAction SilentlyContinue |
                  Select-Object -ExpandProperty FullName
    }
}

if ($found.Count -eq 0) {
    Write-Host "[FAIL] comcntr.dll не найдена ни в одном типовом каталоге."
    Write-Host "       Вероятно, установлена только тонкая клиентская часть -"
    Write-Host "       COM-обмен с этой машины невозможен."
    exit 1
}

Write-Host "Найдено копий: $($found.Count)"
foreach ($dll in $found) { Write-Host "  $dll" }

# --- Шаг 3. Готовая команда регистрации с учётом разрядности ---
$is64 = [Environment]::Is64BitProcess
$psBit = if ($is64) { '64-бит' } else { '32-бит' }

Write-Host ""
Write-Host "Этот PowerShell: $psBit"
Write-Host "Нужна comcntr.dll той же разрядности, иначе регистрация пройдёт"
Write-Host "без ошибки, но класс так и останется незарегистрированным."

foreach ($dll in $found) {
    $bit = if ($dll -match '\bin\64') { '64' } elseif ($dll -match '\bin\') { '32' } else { '?' }
    $reg = if ($is64) { 'C:\Windows\System32\regsvr32.exe' } else { 'C:\Windows\SysWOW64\regsvr32.exe' }
    Write-Host ""
    Write-Host "  [$bit-битная] $dll"
    Write-Host "  Команда: `"$reg`" `"$dll`""
}

if (-not $registered) {
    Write-Host ""
    Write-Host "Запустите PowerShell ОТ ИМЕНИ АДМИНИСТРАТОРА и выполните"
    Write-Host "команду regsvr32 для 64-битной comcntr.dll, затем повторите"
    Write-Host "этот скрипт."
    exit 2
}

# --- Шаг 4. Проверка соединения и запроса ---
try {
    $connection = $connector.Connect('Srvr="' + $server + '";Ref="' + $database + '";')
    Write-Host ""
    Write-Host "[OK] Соединение с базой установлено"
} catch {
    Write-Host "[FAIL] Не удалось подключиться: $($_.Exception.Message)"
    exit 3
}

try {
    $query = $connection.NewObject('Query', 'ВЫБРАТЬ 1 КАК Number')
    $selection = $query.Execute().Select()
    if ($selection.Next()) {
        Write-Host "[OK] Запрос выполнен: $($selection.Value('Number'))"
    }
} catch {
    Write-Host "[FAIL] Запрос не выполнен: $($_.Exception.Message)"
    exit 4
}

Write-Host ""
Write-Host "COM-канал работает. Включайте обмен: ONEC_ENABLED=true"
