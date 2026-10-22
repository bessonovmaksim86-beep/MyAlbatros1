(() => {
    'use strict';

    /*
     * Уведомления склада: блок в нижней части бокового меню, над
     * кнопкой «Выйти».
     *
     * Смысл — сообщить кладовщику о новой работе, не заставляя его
     * переходить в раздел: раз в интервал опроса читается JSON
     * счётчиков, сравнение идёт с предыдущими значениями, и только
     * рост счётчика рождает строку «Новое «Приёмка с БВК» — 1» со
     * звуком и переходом в раздел.
     *
     * Значения держатся в localStorage, а не в переменной памяти:
     * кладовщик ходит между страницами модуля, страница
     * перезагружается, и без хранилища каждое переключение вкладки
     * сигнализировало бы по всем открытым разделам сразу.
     *
     * Первый опрос (ключей в хранилище нет) только запоминает
     * значения и молчит: «новым» считается рост уже известного
     * счётчика, а не любое ненулевое значение — текущие счётчики
     * кладовщик видит бейджами меню.
     */
    const POLL_URL = '/warehouse/notifications/active';
    const STORAGE_KEY = 'warehouseNotificationCounters';

    /* 30 с: быстрее смысла нет — дела склада приходят не секундами. */
    const POLL_INTERVAL_MS = 30000;

    /* Строки живут минуту: засорять меню вчерашними делами незачем. */
    const NOTICE_LIFETIME_MS = 60000;

    /* Больше строк не помещается без прокрутки в свёрнутом меню. */
    const NOTICE_LIMIT = 6;

    function readStored() {
        try {
            const raw = window.localStorage.getItem(STORAGE_KEY);

            return raw ? JSON.parse(raw) : {};
        } catch (error) {
            /*
             * Браузер может отказать в доступе (приватный режим).
             * Без хранилища уведомление всё равно полезно: строки и
             * звук будут на каждой загрузке — это лучше, чем молчать.
             */
            return {};
        }
    }

    function writeStored(values) {
        try {
            window.localStorage.setItem(STORAGE_KEY, JSON.stringify(values));
        } catch (error) {
            /* Хранилище недоступно или переполнено — не критично. */
        }
    }

    /*
     * Короткий двухтональный сигнал на Web Audio API.
     *
     * Файл .wav не заводится: тон синтезируется, поэтому звук есть без
     * бинарника в репозитории и работает офлайн. Частоты высокие,
     * длительность малая — сигнал слышен в цеху, но не мешает.
     *
     * Браузер блокирует звук до первого жеста пользователя; при отказе
     * контекста строка уведомления всё равно показывается — молчание
     * лучше сбоя скрипта.
     */
    let audioContext = null;

    function playChime() {
        try {
            const AudioCtor = window.AudioContext
                || window.webkitAudioContext;

            if (!AudioCtor) {
                return;
            }

            if (!audioContext) {
                audioContext = new AudioCtor();
            }

            if (audioContext.state === 'suspended') {
                audioContext.resume().catch(() => {});
            }

            const start = audioContext.currentTime;

            [880, 1320].forEach((frequency, index) => {
                const oscillator = audioContext.createOscillator();
                const gain = audioContext.createGain();

                oscillator.type = 'sine';
                oscillator.frequency.value = frequency;

                const from = start + index * 0.14;

                gain.gain.setValueAtTime(0.0001, from);
                gain.gain.exponentialRampToValueAtTime(0.18, from + 0.02);
                gain.gain.exponentialRampToValueAtTime(0.0001, from + 0.13);

                oscillator.connect(gain);
                gain.connect(audioContext.destination);

                oscillator.start(from);
                oscillator.stop(from + 0.15);
            });
        } catch (error) {
            /* Звук — вежливость интерфейса, а не его функция. */
        }
    }

    function listHost() {
        return document.getElementById('warehouseNotificationsList');
    }

    function blockHost() {
        return document.getElementById('warehouseNotifications');
    }

    function buildNotice(item) {
        const row = document.createElement('a');

        row.className = 'warehouse-notice__item';
        row.href = item.url;
        row.dataset.key = item.key;

        const label = document.createElement('span');

        label.className = 'warehouse-notice__label';
        label.textContent = 'Новое «' + item.title + '»';

        const count = document.createElement('span');

        count.className = 'warehouse-notice__count';
        count.textContent = item.count;

        row.appendChild(label);
        row.appendChild(count);

        return row;
    }

    function syncVisibility() {
        const block = blockHost();

        if (!block) {
            return;
        }

        const list = listHost();

        block.hidden = !list || list.children.length === 0;
    }

    function expire(row) {
        if (!row || !row.isConnected) {
            return;
        }

        row.classList.add('is-leaving');

        window.setTimeout(() => {
            if (row.isConnected) {
                row.remove();
            }

            syncVisibility();
        }, 400);
    }

    /*
     * Одинаковые пункты суммируются: строка ищется по ключу раздела, и
     * повторное уведомление о нём же увеличивает число, а не добавляет
     * вторую строку — два сигнала об одном деле кладовщик прочёл бы как
     * два разных дела.
     */
    function addNotices(items) {
        const list = listHost();

        if (!list) {
            return;
        }

        items.forEach((item) => {
            const existing = list.querySelector(
                '.warehouse-notice__item[data-key="' + item.key + '"]'
            );

            const row = existing || buildNotice(item);

            if (existing) {
                const counter = existing.querySelector(
                    '.warehouse-notice__count'
                );

                if (counter) {
                    counter.textContent =
                        (Number(counter.textContent) || 0) + item.count;
                }

                existing.classList.remove('is-leaving');
            } else {
                list.appendChild(row);
            }

            window.setTimeout(() => expire(row), NOTICE_LIFETIME_MS);
        });

        /* Длинный перечень не должен вытеснять меню целиком. */
        while (list.children.length > NOTICE_LIMIT) {
            list.removeChild(list.firstElementChild);
        }

        syncVisibility();
    }

    async function poll(known) {
        let items;

        try {
            const response = await fetch(POLL_URL, {
                credentials: 'same-origin',
                headers: {
                    'Accept': 'application/json'
                }
            });

            if (!response.ok) {
                return known;
            }

            items = await response.json();
        } catch (error) {
            /* Сеть или сессия: следующий интервал попробует снова. */
            return known;
        }

        const current = {};
        const fresh = [];

        items.forEach((item) => {
            current[item.key] = item.count;

            const previous = known[item.key];

            /*
             * Ключа нет — это первый опрос в этом браузере: значение
             * запоминают молча, иначе при каждой загрузке страницы
             * звучал бы сигнал по всем разделам сразу.
             */
            if (previous === undefined) {
                return;
            }

            if (item.count > previous) {
                fresh.push({
                    key: item.key,
                    title: item.title,
                    url: item.url,
                    count: item.count - previous
                });
            }
        });

        /*
         * Раздел, по которому счётчик обнулился (дела закрыли),
         * сохраняет ключ со значением 0: отсутствие ключа выглядело бы
         * как «первый опрос», и следующее появление работы прозвучало
         * бы молча.
         */
        Object.keys(known).forEach((key) => {
            if (!(key in current)) {
                current[key] = 0;
            }
        });

        writeStored(current);

        if (fresh.length > 0) {
            addNotices(fresh);
            playChime();
        }

        return current;
    }

    function initializeNotifications() {
        if (!blockHost()) {
            return;
        }

        let known = readStored();

        /*
         * Первый опрос не звучит: если кладовщик открыл страницу и в
         * разделе уже есть работа, это не «новое» — счётчик в меню он
         * видит и так.
         */
        poll(known).then((values) => {
            known = values;

            window.setInterval(() => {
                poll(known).then((next) => {
                    known = next;
                });
            }, POLL_INTERVAL_MS);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener(
            'DOMContentLoaded',
            initializeNotifications
        );
    } else {
        initializeNotifications();
    }
})();
