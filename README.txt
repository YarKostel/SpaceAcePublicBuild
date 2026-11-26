SpaceAce — вертикальный космический шутер (Kotlin + libGDX + Box2D).

Как запустить
  Проще всего — через make (Linux, WSL; на Windows — из Git Bash с установленным GNU make):
    make setup     — первый раз: скачает JDK 21 в ~/.jdks и зависимости Gradle;
    make run       — тесты и запуск игры;   make run-fast — запуск без тестов;
    make test      — тесты;   make dist — готовая сборка в build/install/SpaceAce;
    make help      — все команды.

  Без make нужен JDK 21 (с более новыми JDK сборка не работает).

  Windows:  положите путь к JDK 21 в файл jdk-path.txt и запустите run.bat
            (или run_wout_tests.bat — без прогона тестов).
  Linux / WSL:  ./run.sh            — тесты и запуск игры;
                ./run.sh --no-tests — только запуск.
            run.sh сам ищет JDK 21 в JAVA_HOME, jdk-path.txt и ~/.jdks; в WSL окно открывается через WSLg.
  Вручную:  ./gradlew desktop:run (игра), ./gradlew core:test (тесты).

Управление (по умолчанию, меняется в настройках — шестерёнка в меню)
  стрелки или перетаскивание мышью — движение, S — огонь, A/D/Z/X/C — слоты 1–5,
  P или Esc — пауза, Enter — Play/Continue в меню.

Где что лежит
  internal/  — код игры (Gradle-проект :core), тесты *Test.kt рядом с кодом;
  cmd/       — точки входа: desktop и android;
  assets/    — картинки (sprites/) и шрифт (fonts/);
  docs/      — документация для разработчика.

---

How to run
  JDK 21 is required. Windows: put the JDK 21 path into jdk-path.txt and run run.bat
  (run_wout_tests.bat skips tests). Linux / WSL: ./run.sh (or ./run.sh --no-tests).
  The game code is in internal/, developer documentation is in docs/.
