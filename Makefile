JAVAC ?= javac
JAVA  ?= java
SRC      := $(shell find src -name '*.java')
TEST_SRC := $(shell find test -name '*.java')

.PHONY: all build jar test clean

all: jar

build: out/.stamp

out/.stamp: $(SRC)
	$(JAVAC) -d out $(SRC)
	@touch out/.stamp

jar: build
	jar --create --file html2md.jar --main-class html2md.Main -C out .

test: build $(TEST_SRC)
	$(JAVAC) -d out-test -cp out $(TEST_SRC)
	$(JAVA) -cp out:out-test html2md.Tests

clean:
	rm -rf out out-test html2md.jar
