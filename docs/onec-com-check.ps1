# Проверка COM-соединения с 1С (V83.COMConnector).
# Запускать на машине, где установлена платформа 1С.
# Параметры берутся из переменных окружения приложения.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$server = if ($env:ONEC_SERVER) { $env:ONEC_SERVER } else { 'app1' }
$database = if ($env:ONEC_DATABASE) { $env:ONEC_DATABASE } else { 'albatros' }

Write-Host "Сервер: $server  База: $database"

# Шаг 1. Есть ли COM-объект коннектора (ставится вместе с платформой 1С).
try {
    $connector = New-Object -ComObject V83.COMConnector
    Write-Host "[OK] V83.COMConnector доступен"
} catch {
    Write-Host "[FAIL] V83.COMConnector не зарегистрирован: $($_.Exception.Message)"
    Write-Host "        Платформа 1С не установлена или нужна регистрация comcntr.dll"
    exit 1
}

# Шаг 2. Соединение с базой. Учётные данные не указываются - вход
# под текущим пользователем Windows, как у толстого клиента.
$connectString = 'Srvr="' + $server + '";Ref="' + $database + '";'
Write-Host "Строка соединения: $connectString"

try {
    $connection = $connector.Connect($connectString)
    Write-Host "[OK] Соединение установлено"
} catch {
    Write-Host "[FAIL] Не удалось подключиться: $($_.Exception.Message)"
    exit 2
}

# Шаг 3. Запрос без обращения к таблицам - проверяет сам канал.
try {
    $query = $connection.NewObject('Query', 'ВЫБРАТЬ 1 КАК Number')
    $selection = $query.Execute().Select()

    if ($selection.Next()) {
        Write-Host "[OK] Запрос выполнен, значение: $($selection.Value('Number'))"
    } else {
        Write-Host "[WARN] Запрос выполнен, но строк нет"
    }
} catch {
    Write-Host "[FAIL] Запрос не выполнен: $($_.Exception.Message)"
    exit 3
}

Write-Host ""
Write-Host "COM-канал работает. Можно включать обмен (ONEC_ENABLED=true)."
