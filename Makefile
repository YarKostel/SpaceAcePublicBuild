# SpaceAce — сборка и запуск на Linux (в том числе WSL) и Windows.
#
# Windows: make запускается из Git Bash (нужны Git for Windows и GNU make, например `winget install ezwinports.make`).
# Первый запуск: `make setup` — скачает JDK 21 в ~/.jdks и зависимости Gradle; дальше `make run`.
# Список целей: `make help`.

SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

ifeq ($(OS),Windows_NT)
  PLATFORM := windows
  JDK_ARCHIVE := zip
  JAVA_EXE := java.exe
else
  PLATFORM := linux
  JDK_ARCHIVE := tar.gz
  JAVA_EXE := java
endif

JDK_DIR := $(HOME)/.jdks
JDK_URL := https://api.adoptium.net/v3/binary/latest/21/ga/$(PLATFORM)/x64/jdk/hotspot/normal/eclipse

# JDK 21: JAVA_HOME, затем путь из jdk-path.txt, затем ~/.jdks/jdk-21*. Пусто — не найден (make jdk поставит).
is_jdk21 = $(shell [ -n "$(1)" ] && "$(1)/bin/$(JAVA_EXE)" -version 2>&1 | grep -q 'version "21' && echo "$(1)")
JDK := $(or $(call is_jdk21,$(JAVA_HOME)),\
            $(call is_jdk21,$(shell [ -f jdk-path.txt ] && head -n1 jdk-path.txt | tr -d '\r')),\
            $(call is_jdk21,$(lastword $(sort $(wildcard $(JDK_DIR)/jdk-21*)))))

GRADLE := JAVA_HOME="$(JDK)" ./gradlew --console=plain

.PHONY: help setup jdk deps deps-system run run-fast test dist clean check-jdk

help: ## список целей
	@echo "SpaceAce ($(PLATFORM)), JDK 21: $(or $(JDK),не найден — make jdk)"
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-12s %s\n", $$1, $$2}'

setup: jdk deps ## первый запуск: JDK 21 и зависимости Gradle

jdk: ## скачать JDK 21 (Temurin) в ~/.jdks, если его нет
	@if [ -n "$(JDK)" ]; then echo "JDK 21 уже есть: $(JDK)"; exit 0; fi; \
	mkdir -p "$(JDK_DIR)"; tmp="$(JDK_DIR)/jdk21.$(JDK_ARCHIVE)"; \
	echo "Скачиваю JDK 21 для $(PLATFORM)..."; \
	curl -fSL -o "$$tmp" "$(JDK_URL)"; \
	if [ "$(PLATFORM)" = windows ]; then \
	  powershell -NoProfile -Command "Expand-Archive -Force -Path '$$(cygpath -w "$$tmp")' -DestinationPath '$$(cygpath -w "$(JDK_DIR)")'"; \
	else \
	  tar xzf "$$tmp" -C "$(JDK_DIR)"; \
	fi; \
	rm -f "$$tmp"; echo "Готово: $$(ls -d "$(JDK_DIR)"/jdk-21* | tail -n1)"

deps: check-jdk ## скачать зависимости Gradle и собрать проект без тестов
	@chmod +x gradlew
	$(GRADLE) desktop:classes core:testClasses

deps-system: ## Linux без WSLg: системные библиотеки OpenGL/X11 для окна (нужен sudo)
ifeq ($(PLATFORM),linux)
	sudo apt-get update && sudo apt-get install -y curl libgl1 libxrandr2 libxcursor1 libxi6 libxxf86vm1
else
	@echo "На Windows системные библиотеки не нужны."
endif

run: check-jdk ## тесты, затем запуск игры
	$(GRADLE) -q core:test desktop:run

run-fast: check-jdk ## запуск игры без тестов
	$(GRADLE) -q desktop:run -x test

test: check-jdk ## автотесты (отчет: internal/build/reports/tests/test/index.html)
	$(GRADLE) core:test

dist: check-jdk ## готовая сборка игры: build/install/SpaceAce (запуск — bin/SpaceAce или bin/SpaceAce.bat)
	$(GRADLE) desktop:installDist
	@rm -rf build/install && mkdir -p build/install && cp -r cmd/desktop/build/install/SpaceAce build/install/
	@echo "Сборка: build/install/SpaceAce, запуск — bin/SpaceAce (Linux) или bin/SpaceAce.bat (Windows); нужна Java 17+"

clean: check-jdk ## удалить результаты сборки
	$(GRADLE) clean
	rm -rf build

check-jdk:
	@[ -n "$(JDK)" ] || { echo "JDK 21 не найден. Выполните: make jdk (или укажите путь в jdk-path.txt)"; exit 1; }
