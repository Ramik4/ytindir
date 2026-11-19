# 4PDA post template

The post for a new YTindir release in the 4PDA thread (BBCode).

How to fill it in:

- `X.Y.Z`: the version, e.g. `2.18.0`
- `BUILD`: the GitHub Actions build number from the release tag (`vX.Y.Z-buildBUILD`)
- The "What's new" lists: the Russian notes (`notes:ru`) from `release-notes/vX.Y.Z.md`, each line starting with `•`
- The second section is `🔧 Изменения и улучшения:` (the notes' "Changes and improvements"); there is no "bug fixes and other improvements" line at the end
- The arm64 APK (`YTindir-vX.Y.Z_arm64.apk`) is attached to the post on 4PDA, under the first ▷ line
- `SIZE`: the universal APK's size in MB (decimal, as the release page shows it); the link always points to the latest release, so it never changes

```
[size=2][b][color="#00cc66"] YTindir[/color][/size][/b] vX.Y.Z ✓

[b]Тип:[/b] Новая версия
[b]Версия:[/b] X.Y.Z
[b]Краткое описание:[/b] YTindir - загрузчик видео и аудио на базе yt-dlp (build BUILD)

[spoiler=☑ Что нового »]

[b]✨ Новые возможности:[/b]
• …

[b]🔧 Изменения и улучшения:[/b]
• …

[/spoiler]
[center][b]Скачать ↆ Download[/b][/center]

▷ [b][color="#00cc66"]YTindir[/color][/b] vX.Y.Z ✓

▷ [b][color="#00cc66"]YTindir[/color][/b] vX.Y.Z (universal) ✓
[url="https://github.com/RAMiK4/ytindir/releases/latest/download/YTindir-x-universal.apk"][color="#808080"]YTindir-vX.Y.Z_universal.apk[/color][/url] (SIZE МБ)
```

## Example: 2.18.1

```
[size=2][b][color="#00cc66"] YTindir[/color][/size][/b] v2.18.1 ✓

[b]Тип:[/b] Новая версия
[b]Версия:[/b] 2.18.1
[b]Краткое описание:[/b] YTindir - загрузчик видео и аудио на базе yt-dlp (build 118)

[spoiler=☑ Что нового »]

[b]🔧 Изменения и улучшения:[/b]
• «Все параметры» в меню быстрой загрузки YouTube RLX и YT Music RLX сразу открывает окно загрузки со всеми настройками, а не быстрый список YTindir; там поле формата открывает полный список форматов
• Окно загрузки на всех вкладках открывается целиком, до ссылки внизу, без подтягивания вверх
• «Настройка аудио» в окне загрузки в две строки: Миниатюра, Текст песни, Битрейт / Вырезать, Шаблон имени файла

[/spoiler]
[center][b]Скачать ↆ Download[/b][/center]

▷ [b][color="#00cc66"]YTindir[/color][/b] v2.18.1 ✓

▷ [b][color="#00cc66"]YTindir[/color][/b] v2.18.1 (universal) ✓
[url="https://github.com/RAMiK4/ytindir/releases/latest/download/YTindir-x-universal.apk"][color="#808080"]YTindir-v2.18.1_universal.apk[/color][/url] (197 МБ)
```
