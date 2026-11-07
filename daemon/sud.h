/*
 * sud.h - shared declarations for the post-fs-data/service daemon.
 *
 * sud runs standalone, invoked directly by the kernel's
 * call_usermodehelper() before most of Android's userspace exists (see
 * docs/PHASE4_DESIGN.md) - no Android NDK/bionic-specific APIs assumed
 * beyond what a minimal libc provides, no JVM, no Binder.
 */
#ifndef SUD_H
#define SUD_H

#include <stdbool.h>

#define SUD_MODULES_DIR "/data/adb/modules"
#define SUD_MAX_MODULES 128
#define SUD_MAX_PATH 512
#define SUD_SCRIPT_TIMEOUT_SEC 20

struct sud_module {
	char dir_name[256];       /* directory name under SUD_MODULES_DIR, also module id by convention */
	char id[256];              /* module.prop "id=" - should match dir_name but isn't trusted to */
	char name[256];
	char version[64];
	bool skip_mount;           /* "skip_mount" marker file present */
	bool has_post_fs_data_sh;
	bool has_service_sh;
};

/* module_scan.c */

/*
 * Scans SUD_MODULES_DIR, fills `out` with every module that should be
 * active this boot (not disable/remove-flagged, module.prop parses).
 * Order: most-recently-modified module.prop last and first in
 * mount-precedence order is deliberately NOT guaranteed here - caller
 * (overlay_mount.c) decides lowerdir ordering; this just enumerates.
 * Returns the number of modules written into `out` (<= SUD_MAX_MODULES),
 * or -1 on a directory-open failure.
 */
int sud_scan_modules(struct sud_module *out, int max);

/* Parses one module.prop file into *mod (id/name/version fields only -
 * author/description aren't needed by the daemon, the manager app reads
 * those itself). Returns false if the file is missing/unparseable. */
bool sud_parse_module_prop(const char *module_dir, struct sud_module *mod);

/* overlay_mount.c */

/*
 * Builds the OverlayFS lowerdir= list for `mount_point` (e.g. "/system")
 * across every module in `modules` (count entries) that has a matching
 * subdirectory under its system/ tree, with modules[] order used as
 * lowerdir order (first entry = topmost/highest-priority layer), then
 * mounts it read-only over `mount_point`. No-op (returns 0) if no
 * module touches this mount point. Returns 0 on success/no-op, -1 on a
 * mount(2) failure (errno set).
 */
int sud_overlay_apply(const struct sud_module *modules, int count,
		       const char *mount_point);

/* Runs <module_dir>/post-fs-data.sh or <module_dir>/service.sh if it
 * exists and is executable, waiting up to SUD_SCRIPT_TIMEOUT_SEC before
 * killing it. Returns 0 if the script ran (or didn't exist - that's not
 * an error), -1 if it existed but couldn't be executed/timed out. */
int sud_run_module_script(const char *module_dir, const char *script_name);

#endif /* SUD_H */
