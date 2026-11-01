.PHONY: lint test clean build release check worktree-clean

# Gradle wrapper 的调用方式随平台而异：类 Unix 用 ./gradlew，原生 Windows 用 cmd /c gradlew.bat
# （原因与覆盖方式见 AGENTS.md「常用命令」）。
# 需要时可在命令行覆盖，例如：make GRADLEW=./gradlew lint（Git Bash 用户）
ifeq ($(OS),Windows_NT)
  GRADLEW ?= cmd /c gradlew.bat
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

# 清理 .agents/worktrees/ 下的工作树：移除工作树，再强制删除其检出的分支。
# 必须在仓库根目录执行（在某个工作树内执行会删掉当前目录）；Windows 需在 Git Bash/WSL 下运行。
worktree-clean:
	@root=$$(git rev-parse --show-toplevel); \
	dir="$$root/.agents/worktrees"; \
	if [ ! -d "$$dir" ]; then echo "no worktrees under .agents/worktrees"; exit 0; fi; \
	git worktree list --porcelain | awk '/^worktree /{print $$2}' | grep -F "$$dir/" | while IFS= read -r wt; do \
		branch=$$(git -C "$$wt" rev-parse --abbrev-ref HEAD 2>/dev/null || true); \
		echo "removing worktree $$wt"; \
		git worktree remove --force "$$wt" || echo "warning: failed to remove $$wt" >&2; \
		if [ -n "$$branch" ] && [ "$$branch" != "HEAD" ]; then git branch -D "$$branch" || true; fi; \
	done; \
	git worktree prune; \
	find "$$dir" -type d -empty -delete 2>/dev/null || true; \
	left=$$(git worktree list --porcelain | grep -cF "$$dir/" || true); \
	if [ "$$left" -ne 0 ]; then echo "error: $$left worktree(s) under .agents/worktrees still registered" >&2; exit 1; fi
