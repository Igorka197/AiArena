# Player Spawn Egg (Fabric 1.20.1)

Мод для Minecraft 1.20.1 на **Fabric**: добавляет **яйцо призыва игрока**.
Призванный «игрок» — это моб с моделью и скином Стива, у которого полностью
отключён ИИ: он просто **стоит на месте**, не ходит, не толкается и не отбрасывается.

## Что внутри

| Файл | Назначение |
|---|---|
| `build.gradle`, `settings.gradle`, `gradle.properties` | шаблон фабрик-мода (Loom 1.6, Yarn 1.20.1+build.10, Loader 0.15.11, Fabric API 0.92.2) |
| `PlayerEggMod.java` | регистрация сущности `playeregg:clone_player` и предмета `playeregg:clone_player_spawn_egg` |
| `entity/ClonePlayerEntity.java` | сама сущность: без целей ИИ, скорость 0, knockback resistance 1 |
| `client/PlayerEggClientMod.java` + `ClonePlayerEntityRenderer.java` | рендер моделью игрока (`EntityModelLayers.PLAYER`) + предмет в руке |
| `assets/playeregg/...` | модель предмета и переводы (en_us / ru_ru) |

Яйцо автоматически добавляется во вкладку креатива **«Яйца призыва»**.

## Сборка

Нужны JDK 17 и интернет (Gradle сам скачает зависимости):

```bash
# один раз сгенерировать wrapper (jar не хранится в репо)
gradle wrapper --gradle-version 8.7

./gradlew build          # готовый jar -> build/libs/playeregg-1.0.0.jar
./gradlew runClient      # запустить тестовый клиент
```

Готовый jar кинуть в `mods/` рядом с **Fabric Loader 0.15+** и **Fabric API**.

## Использование

Креатив → вкладка «Яйца призыва» → «Яйцо призыва игрока», ПКМ по блоку.
Или командой:

```
/give @s playeregg:clone_player_spawn_egg
/summon playeregg:clone_player ~ ~ ~
```
