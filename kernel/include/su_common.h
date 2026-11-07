/*
 * su_common.h - internal kernel-module-only declarations shared between
 * su_main.c, su_hook.c, su_allowlist.c, su_ioctl.c, su_boot_trigger.c,
 * su_hide.c and su_mount_isolate.c. Not exposed to userspace (see
 * su_uapi.h for that).
 */
#ifndef _SU_COMMON_H
#define _SU_COMMON_H

#include <linux/types.h>
#include <linux/wait.h>

#define SU_MODULE_TAG "su_core"
#define su_log(fmt, ...)  pr_info(SU_MODULE_TAG ": " fmt, ##__VA_ARGS__)
#define su_warn(fmt, ...) pr_warn(SU_MODULE_TAG ": " fmt, ##__VA_ARGS__)
#define su_err(fmt, ...)  pr_err(SU_MODULE_TAG ": " fmt, ##__VA_ARGS__)

/* ---- allowlist (su_allowlist.c) ---- */

#define SU_MAX_ALLOWLIST 64

int  su_allowlist_init(void);
void su_allowlist_exit(void);
int  su_allowlist_grant(uid_t uid, u32 flags);
int  su_allowlist_revoke(uid_t uid);
bool su_allowlist_check(uid_t uid, u32 *flags_out);
int  su_allowlist_count(void);

/* ---- pending-request notification (su_allowlist.c, consumed by su_ioctl.c) ---- */

extern wait_queue_head_t su_request_waitq;
void su_notify_unknown_uid(uid_t uid);
bool su_pop_pending_request(uid_t *uid_out);

/* ---- hook framework (su_hook.c) ---- */

int  su_hooks_install(void);
void su_hooks_uninstall(void);

/* Symbol resolution via the kallsyms_lookup_name kprobe trick, exposed
 * for future hook backends (syscall-table patch, manual source hook,
 * su_mount_isolate.c's copy_mnt_ns resolution). */
unsigned long su_lookup_symbol(const char *name);

/* ---- Phase 4: one-shot boot-stage trigger (su_boot_trigger.c) ----
 * Called from su_hook.c's existing do_execveat_common pre_handler (not
 * a separate kprobe - see su_boot_trigger.c's file comment) with the
 * path of whatever /init just execve'd. No-ops after it has already
 * fired once this boot. */
void su_boot_trigger_check(const char *exec_path);

/* ---- Phase 5: path visibility (su_hide.c) ----
 * Visibility is gated on the same su_allowlist_check() used for su
 * grants - see docs/PHASE5_DESIGN.md "Allowlist-check reuse". */
int  su_hide_init(void);
void su_hide_exit(void);
int  su_hooks_install_hide(void);   /* installs the vfs_statx + getdents64 kretprobes */
void su_hooks_uninstall_hide(void);
int  su_hide_add_path(const char *path, bool subtree);
int  su_hide_remove_path(const char *path);
bool su_hide_path_is_hidden(const char *path);

/* ---- Phase 5: per-task mount namespace isolation (su_mount_isolate.c) ----
 * Called from su_hook.c's execve pre_handler, alongside the boot
 * trigger check - see that file's header comment for the high-risk
 * caveat around unexported copy_mnt_ns resolution. */
int  su_mount_isolate_init(void);
void su_mount_isolate_exit(void);
void su_mount_isolate_check(uid_t uid);

/* ---- ioctl device node (su_ioctl.c) ---- */

int  su_ioctl_init(void);
void su_ioctl_exit(void);

#endif /* _SU_COMMON_H */
