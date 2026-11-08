# Standalone Makefile - neither binary here is built by the Gradle
# project (see docs/PHASE4_DESIGN.md: sud has to run before a JVM
# exists; su_client is a tiny standalone exec-wrapper, no reason to tie
# it to Gradle either). Cross-compile with an Android NDK toolchain,
# e.g.:
#
#   make CC=aarch64-linux-android29-clang
#
# (substitute whatever NDK clang wrapper matches the target API level -
# there is no single right one, hence it's not hardcoded here).

CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra -static

SUD_SRCS := sud.c module_scan.c overlay_mount.c
SUD_OBJS := $(SUD_SRCS:.c=.o)

.PHONY: all clean
all: sud su_client

sud: $(SUD_OBJS)
	$(CC) $(CFLAGS) -o $@ $(SUD_OBJS)

# su_client.c has no dependency on sud.h (it is deliberately standalone -
# see its own file header) - built as its own single-file target rather
# than folded into sud's object list.
su_client: su_client.o
	$(CC) $(CFLAGS) -o $@ su_client.o

%.o: %.c sud.h
	$(CC) $(CFLAGS) -c -o $@ $<

su_client.o: su_client.c
	$(CC) $(CFLAGS) -c -o $@ $<

clean:
	rm -f $(SUD_OBJS) su_client.o sud su_client
