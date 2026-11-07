/*
 * su_common.h - internal kernel-module-only declarations shared between
 * su_main.c, su_hook.c, su_allowlist.c, su_ioctl.c and
 * su_boot_trigger.c. Not exposed to userspace (see su_uapi.h for that).
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
 * for future hook backends (syscall-table patch, manual source hook). */
unsigned long su_lookup_symbol(const char *name);

/* ---- Phase 4: one-shot boot-stage trigger (su_boot_trigger.c) ----
 * Called from su_hook.c's existing do_execveat_common pre_handler (not
 * a separate kprobe - see su_boot_trigger.c's file comment) with the
 * path of whatever /init just execve'd. No-ops after it has already
 * fired once this boot. */
void su_boot_trigger_check(const char *exec_path);

/* ---- ioctl device node (su_ioctl.c) ---- */

int  su_ioctl_init(void);
void su_ioctl_exit(void);

#endif /* _SU_COMMON_H */
