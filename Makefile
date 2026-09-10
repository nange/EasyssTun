.PHONY: lint test clean build release check

# Gradle wrapper 的文件名与可执行方式随平台而异：
#   类 Unix（macOS/Linux/WSL/Git Bash/MSYS）→ shell 脚本 ./gradlew
#   原生 Windows（cmd.exe/PowerShell）    → 批处理 gradlew.bat（写 ./gradlew 会报 “不是内部或外部命令”）
# 这里以 make 实际执行 recipe 的 shell 为准：sh/bash 用脚本，cmd 用批处理，
# 这样装了 Git Bash 的 Windows 机器也能正常走 make。
# 需要时可在命令行覆盖，例如：make GRADLEW=./gradlew lint
ifeq ($(OS),Windows_NT)
  ifneq (,$(findstring sh,$(notdir $(SHELL))))
    GRADLEW ?= ./gradlew
  else
    GRADLEW ?= gradlew.bat
  endif
else
  GRADLEW ?= ./gradlew
endif

lint:
	$(GRADLEW) lintDebug

test:
	$(GRADLEW) test

clean:
	$(GRADLEW) clean

build:
	$(GRADLEW) assembleDebug --warning-mode all

release:
	$(GRADLEW) assembleRelease --warning-mode all

check: lint test
