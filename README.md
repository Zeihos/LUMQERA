# LUMQERA

![LUMQERA cover](assets/branding/cover.jpg)

**LUMQERA** — Minecraft Java-сервер с PvP, кланами, защитой территорий, экономикой, клановыми войнами, контрольными точками и игровым сайтом.

**Текущая версия ядра:** `1.0.0-RC7` · **Платформа:** Paper 26.3 · **Java:** JDK 25 · **Gradle:** 9.1.0

> **Статус:** разрабатывается. GitHub Actions проверяет компиляцию и автоматические тесты; полноценная проверка плагина в запущенном Paper и нагрузочное тестирование ещё не завершены.

## Структура проекта

```text
LUMQERA/
├── .github/workflows/          # Сборка и тестирование
├── assets/
│   ├── branding/               # Логотип, обложка, аватар
│   └── social/                 # Изображения и тексты публикаций
├── docs/screenshots/           # Скриншоты сайта
├── server/
│   ├── LumqeraCore/            # Исходники Java-плагина, Gradle, тесты
│   └── paper/                  # Пример настроек Paper (без миров и JAR)
├── website/                    # Сайт и локальный Node.js-сервер
├── build-plugin-windows.bat    # Сборка JAR на Windows
├── deploy-plugin-windows.bat   # Установка JAR на выключенный Paper
├── start-server-windows.bat    # Запуск локального сервера
├── start-website-windows.bat   # Локальный просмотр сайта
├── sync-rating-windows.bat     # Перенос рейтинга на сайт
└── backup-server-windows.bat   # Резервирование данных остановленного сервера
```

## Как собрать плагин

Установите **JDK 25**. Из корня репозитория запустите `build-plugin-windows.bat`. Скрипт при необходимости предложит скачать Gradle 9.1.0 с проверкой SHA-256.

Либо используйте терминал с установленными JDK 25 и Gradle 9.1.0:

```powershell
cd server/LumqeraCore
gradle clean build --no-daemon
```

Результат: `server/LumqeraCore/build/libs/LumqeraCore-1.0.0-RC7.jar`.

**Без локальной сборки:** откройте [GitHub Actions](https://github.com/Zeihos/LUMQERA/actions/workflows/build.yml) и скачайте JAR в разделе **Artifacts** успешного запуска для Windows или Linux.

## Локальный запуск Minecraft-сервера

1. Скачайте подходящий `paper.jar` для Minecraft **26.3** с [официального сайта Paper](https://papermc.io/downloads/paper) и положите его в `server/paper/`.
2. При необходимости настройте `server.properties`: создайте его запуском сервера или возьмите `server/paper/server.properties.example` за образец.
3. При выключенном сервере запустите `deploy-plugin-windows.bat` — плагин будет скопирован в `server/paper/plugins/LumqeraCore.jar`.
4. Запустите `start-server-windows.bat`. На первом старте прочитайте Minecraft EULA и **вручную** установите `eula=true` в созданном `eula.txt` только при согласии.
5. После полной загрузки проверьте в консоли `plugins` и команды `/clan`, `/balance`, `/claim`, `/war` и `/report`.

**Важно:** перед заменой JAR и резервным копированием полностью остановите Paper командой `stop`. Не размещайте реальные миры, `eula.txt`, журналы, данные игроков и конфигурации с секретами в публичном репозитории.

## Сайт

В папке `website/` находится самостоятельный сайт. Чтобы открыть локально, запустите `start-website-windows.bat` (требуется Node.js), затем перейдите на [http://127.0.0.1:3000](http://127.0.0.1:3000).

Плагин экспортирует публичную статистику в `server/paper/plugins/LumqeraCore/export/public-stats.json`. После её создания запустите `sync-rating-windows.bat`, чтобы обновить `website/data/public-stats.json`.

## Версии и сохранность данных

Коммиты и автоматические проверки храните в GitHub; собранные JAR — в артефактах Actions, а не в исходниках. Рабочие миры сервера должны находиться **локально**, вне Git.

Перед реструктуризацией сохранена отдельная [резервная ветка](https://github.com/Zeihos/LUMQERA/tree/backup/pre-restructure-2026-11-17) со старым расположением файлов. Она содержит также когда-то загруженные игровые данные: не используйте её как рабочую и не добавляйте туда новые данные игроков.

Сайт проекта: **https://lumqera.ru**.
