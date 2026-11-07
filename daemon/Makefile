# Standalone Makefile - sud is NOT built by the Gradle project (see
# docs/PHASE4_DESIGN.md: it has to run before a JVM exists). Cross-
# compile with an Android NDK toolchain, e.g.:
#
#   make CC=aarch64-linux-android29-clang
#
# (substitute whatever NDK clang wrapper matches the target API level -
# there is no single right one, hence it's not hardcoded here).

CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra -static
SRCS := sud.c module_scan.c overlay_mount.c
OBJS := $(SRCS:.c=.o)
BIN := sud

.PHONY: all clean
all: $(BIN)

$(BIN): $(OBJS)
	$(CC) $(CFLAGS) -o $@ $(OBJS)

%.o: %.c sud.h
	$(CC) $(CFLAGS) -c -o $@ $<

clean:
	rm -f $(OBJS) $(BIN)
