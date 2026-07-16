.PHONY: lint test clean build check

lint:
	./gradlew lintDebug

test:
	./gradlew test

clean:
	./gradlew clean

build:
	./gradlew assembleDebug

check: lint test
