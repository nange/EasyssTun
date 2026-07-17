.PHONY: lint test clean build release check

lint:
	./gradlew lintDebug

test:
	./gradlew test

clean:
	./gradlew clean

build:
	./gradlew assembleDebug --warning-mode all

release:
	./gradlew assembleRelease --warning-mode all

check: lint test
