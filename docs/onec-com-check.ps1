# ----------------------------------------------------------------------
# Диагностика COM-соединения с 1С.
#
# Проверка идёт в 32-битном PowerShell: платформа 1С ставится в каталог
# 1cv8_x86, то есть сборка 32-битная, а 32-битный COM-класс из
# 64-битного процесса не создаётся.
#
# ВАЖНО. Каждый шаг проверяется отдельно, и успех печатается ТОЛЬКО
# после того, как соответствующая операция реально выполнена. Предыдущая
# версия скрипта печатала OK через точку с запятой, и OK появлялся даже
# когда New-Object падал с Class not registered.
# ----------------------------------------------------------------------

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$server = 'app1'
$database = 'albatros'
$username = ''
$password = ''

if ($env:ONEC_SERVER) { $server = $env:ONEC_SERVER }
if ($env:ONEC_DATABASE) { $database = $env:ONEC_DATABASE }
if ($env:ONEC_USERNAME) { $username = $env:ONEC_USERNAME }
if ($env:ONEC_PASSWORD) { $password = $env:ONEC_PASSWORD }

Write-Host "Сервер: $server  База: $database"
Write-Host "Разрядность этого процесса: $([Environment].Is64BitProcess)"
Write-Host ""

# --- Шаг 1. Ищем comcntr.dll -----------------------------------------
Write-Host "Шаг 1. Поиск comcntr.dll"

$roots = @(
    "$env:LOCALAPPDATA\Programs\1cv8_x86",
    "$env:LOCALAPPDATA\Programs\1cv8",
    'C:\Program Files\1C',
    'C:\Program Files (x86)\1C',
    'C:\Program Files\1cv8',
    'C:\Program Files (x86)\1cv8'
)

# Локальная установка 1С кладёт dll в AppData того пользователя, под
# которым платформа ставилась. Если диагностику запустили «от имени
# администратора», $env:LOCALAPPDATA указывает на профиль админа и dll в
# рабочем профиле не находится - скрипт отвечает «платформа не
# установлена» на полностью исправной машине. Поэтому перебираем AppData
# всех профилей. Дубликаты корней безвредны: Test-Path просто повторно
# проверяет тот же каталог.
foreach ($userProfile in Get-ChildItem 'C:\Users' -Directory -ErrorAction SilentlyContinue) {
    $roots += "$($userProfile.FullName)\AppData\Local\Programs\1cv8_x86"
    $roots += "$($userProfile.FullName)\AppData\Local\Programs\1cv8"
}

$dlls = @()
foreach ($root in $roots) {
    if (Test-Path $root) {
        $dlls += Get-ChildItem -Path $root -Filter 'comcntr.dll' -Recurse -ErrorAction SilentlyContinue
    }
}

if ($dlls.Count -eq 0) {
    Write-Host "  [FAIL] comcntr.dll не найдена. Платформа 1С не установлена."
    Write-Host "         COM-обмен с этой машины невозможен."
    exit 1
}

foreach ($d in $dlls) { Write-Host "  найдена: $($d.FullName)" }

# Разрядность dll определяется по каталогу установки, а не по тому,
# откуда запущен скрипт: bin\64 - 64-битная сборка, всё остальное - 32.
$dll = $dlls[0]
$dllIs64 = $dll.FullName -match '\bin\64'
Write-Host "  разрядность dll: $(if ($dllIs64) { '64-бит' } else { '32-бит' })"

# --- Шаг 2. Зарегистрирован ли класс ----------------------------------
Write-Host ""
Write-Host "Шаг 2. Проверка регистрации V83.COMConnector"

$registered = $false
try {
    $connector = New-Object -ComObject V83.COMConnector
    $registered = $true
    Write-Host "  [OK] класс доступен"
} catch {
    Write-Host "  [FAIL] класс недоступен: $($_.Exception.Message)"
}

if (-not $registered) {
    # Каталог regsvr32 выбирается по разрядности dll. В Windows имена
    # перепутаны исторически: SysWOW64 содержит 32-битные компоненты,
    # System32 - 64-битные.
    $regTool = if ($dllIs64) { "$env:SystemRoot\System32\regsvr32.exe" } else { "$env:SystemRoot\SysWOW64\regsvr32.exe" }

    Write-Host ""
    Write-Host "  Нужно зарегистрировать dll. Выполните в PowerShell"
    Write-Host "  ОТ ИМЕНИ АДМИНИСТРАТОРА:"
    Write-Host ""
    Write-Host "    & '$regTool' '$($dll.FullName)'"
    Write-Host ""
    Write-Host "  Дождитесь окна 'Успешно', затем повторите этот скрипт."
    exit 2
}

# --- Шаг 3. Соединение с базой ---------------------------------------
if ($username -ne '') {
    Write-Host ""
    Write-Host "Шаг 3. Соединение с базой (пользователь 1С: $username)"
    $connectString = 'Srvr="' + $server + '";Ref="' + $database + '";Usr="' + $username + '";Pwd="' + $password + '";'
} else {
    Write-Host ""
    Write-Host "Шаг 3. Соединение с базой (вход под текущим пользователем Windows)"
    $connectString = 'Srvr="' + $server + '";Ref="' + $database + '";'
}
# Пароль в консоль не печатается: строка соединения остаётся в истории
# окна и в перенаправленном выводе.
$shown = $connectString -replace 'Pwd="[^"]*"', 'Pwd="***"'
Write-Host "  $shown"

try {
    $connection = $connector.Connect($connectString)
    Write-Host "  [OK] соединение установлено"
} catch {
    Write-Host "  [FAIL] $($_.Exception.Message)"
    exit 3
}

# --- Шаг 4. Запрос ----------------------------------------------------
Write-Host ""
Write-Host "Шаг 4. Выполнение запроса через канал"

try {
    $query = $connection.NewObject('Query', 'ВЫБРАТЬ 1 КАК Number')
    $selection = $query.Execute().Select()

    if ($selection.Next()) {
        Write-Host "  [OK] запрос выполнен, Number = $($selection.Value('Number'))"
    } else {
        Write-Host "  [FAIL] запрос не вернул строк"
        exit 4
    }
} catch {
    Write-Host "  [FAIL] $($_.Exception.Message)"
    exit 4
}

Write-Host ""
Write-Host "ВСЁ РАБОТАЕТ. Для приложения задайте:"
Write-Host "  `$env:ONEC_ENABLED = 'true'"
if (-not $dllIs64) {
    Write-Host "  `$env:ONEC_POWERSHELL_PATH = 'C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe'"
}
