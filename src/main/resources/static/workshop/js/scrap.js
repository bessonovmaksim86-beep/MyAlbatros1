/**
 * Предпросмотр ячейки в форме браковки.
 *
 * При вводе номера мастер видит изделие, ЗНП и помехи до
 * нажатия кнопки — не получает отказ и не тратит время на
 * повторный ввод.
 *
 * Запрос идёт на /workshop/scrap/preview, который отдаёт JSON
 * с полями fullNumber, found, cellLabel, kindLabel, znpNumber,
 * location и массивом problems.
 */
document.addEventListener('DOMContentLoaded', function () {

    var numberInput = document.getElementById('fullNumber');
    var preview     = document.getElementById('scrapPreview');

    if (!numberInput || !preview) {
        return;
    }

    /*
     * Блокируем повторные запросы: пользователь быстро стирает и
     * вводит снова, а сервер не обязан выдерживать гонку ответов.
     */
    var debounce = null;
    var last     = '';

    numberInput.addEventListener('input', function () {
        var value = this.value.replace(/\D/g, '');

        /* Стерли — прячем панель */
        if (value.length < 9) {
            last = '';
            preview.hidden = true;
            preview.classList.remove('is-ok');
            return;
        }

        /* Уже запрашивали этот номер — не повторяем */
        if (value === last) {
            return;
        }

        last = value;

        /* Таймер: ждём, пока пользователь закончит вводить */
        clearTimeout(debounce);

        debounce = setTimeout(function () {
            fetch('/workshop/scrap/preview?number=' + encodeURIComponent(value))
                .then(function (r) { return r.json(); })
                .then(function (data) { render(data); })
                .catch(function () { /* сетевая ошибка — панель не меняем */ });
        }, 300);
    });

    /*
     * Рендерит панель предпросмотра.
     *
     * problems — пустой массив значит, что заявка пройдёт.
     * Иначе показываем помехи и убираем зелёную обводку.
     */
    function render(data) {
        var cellEl  = document.getElementById('previewCell');
        var kindEl  = document.getElementById('previewKind');
        var znpEl   = document.getElementById('previewZnp');
        var locEl   = document.getElementById('previewLocation');
        var probEl  = document.getElementById('previewProblems');

        if (!data.found) {
            /* Номер не в реестре */
            preview.hidden = false;
            preview.classList.remove('is-ok');
            cellEl.textContent = '—';
            kindEl.textContent = '—';
            znpEl.textContent  = '—';
            locEl.textContent  = '—';
            showProblems(data.problems, probEl);
            return;
        }

        /* Ячейка найдена */
        preview.hidden = false;
        cellEl.textContent = data.cellLabel || '—';
        kindEl.textContent = data.kindLabel || '—';
        znpEl.textContent  = data.znpNumber || '—';
        locEl.textContent  = data.location || '—';

        if (data.problems && data.problems.length > 0) {
            /* Есть помехи — покажем красным */
            preview.classList.remove('is-ok');
            showProblems(data.problems, probEl);
        } else {
            /* Всё ок — зелёная панель */
            preview.classList.add('is-ok');
            hideProblems(probEl);
        }
    }

    /*
     * Показывает список помех.
     */
    function showProblems(problems, container) {
        if (!problems || problems.length === 0) {
            hideProblems(container);
            return;
        }

        container.innerHTML = '';

        for (var i = 0; i < problems.length; i++) {
            var li = document.createElement('li');
            li.textContent = problems[i];
            container.appendChild(li);
        }

        container.hidden = false;
    }

    /*
     * Скрывает список помех.
     */
    function hideProblems(container) {
        container.innerHTML = '';
        container.hidden = true;
    }
});
