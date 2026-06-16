.PHONY: test lint compile assembly native clean

JAVA_HOME ?= $(shell echo "$$JAVA_HOME")
SBT = sbt

test:
	$(SBT) test

lint:
	$(SBT) scalafmtCheckAll

format:
	$(SBT) scalafmtAll

compile:
	$(SBT) compile

assembly:
	$(SBT) assembly

native: assembly
	bash deployment/build-native.sh

clean:
	$(SBT) clean
	rm -f deployment/bootstrap deployment/bgg-api-native.zip

run:
	$(JAVA_HOME)/bin/java -cp target/scala-3.3.8/bgg-api-assembly.jar bgg.lambda.ApiHandler
