/*
 * su_hook.c - Hook framework + execve interception.
 *
 * NOTE on filename extraction: do_execveat_common's publicly documented
 * signature (fs/exec.c, upstream Linux) is roughly
 *     do_execveat_common(int fd, struct filename *filename, ...)
 * On arm64, kprobes see arguments in x0-x5 before the prologue runs, so
 * regs->regs[1] holds the "struct filename *" argument. struct filename
 * itself is a public kernel header type (include/linux/fs.h) with a
 * "const char *name" field, already populated in kernel memory by the
 * time do_execveat_common runs, so no copy_from_user() is needed here.
 *
 * CAVEAT: not yet validated on a real GKI 2.0 build. If wrong, the safe
 * failure mode is "no process matches" or "every process matches" -
 * elevation still requires an allowlist hit either way.
 */

#include <linux/kprobes.h>
#include <linux/sched.h>
#include <linux/cred.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/fs.h>
#include "su_common.h"

#define SU_CLIENT_PATH "/system/bin/su_client"
#define SU_MAX_HOOKS 8

struct su_hook_entry {
const char *symbol;
struct kprobe kp;
bool installed;
};

static struct su_hook_entry hooks[SU_MAX_HOOKS];
static int hook_count;

typedef unsigned long (*kallsyms_lookup_name_t)(const char *name);
static kallsyms_lookup_name_t su_kallsyms_lookup_name;

static int su_resolve_kallsyms(void)
{
struct kprobe kp = {
.symbol_name = "kallsyms_lookup_name",
};
int ret = register_kprobe(&kp);

if (ret < 0) {
su_err("failed to resolve kallsyms_lookup_name (ret=%d)\n", ret);
return ret;
}

su_kallsyms_lookup_name = (kallsyms_lookup_name_t)kp.addr;
unregister_kprobe(&kp);

if (!su_kallsyms_lookup_name) {
su_err("kallsyms_lookup_name resolved to NULL\n");
return -ENOENT;
}
return 0;
}

static int su_register_hook(const char *symbol, kprobe_pre_handler_t pre)
{
struct su_hook_entry *h;
int ret;

if (hook_count >= SU_MAX_HOOKS)
return -ENOSPC;

h = &hooks[hook_count];
memset(&h->kp, 0, sizeof(h->kp));
h->symbol = symbol;
h->kp.symbol_name = symbol;
h->kp.pre_handler = pre;

ret = register_kprobe(&h->kp);
if (ret < 0) {
su_err("failed to hook '%s' (ret=%d)\n", symbol, ret);
return ret;
}

h->installed = true;
hook_count++;
su_log("hooked '%s' at %px\n", symbol, h->kp.addr);
return 0;
}

static void su_unregister_all_hooks(void)
{
int i;

for (i = 0; i < hook_count; i++) {
if (hooks[i].installed) {
unregister_kprobe(&hooks[i].kp);
hooks[i].installed = false;
}
}
hook_count = 0;
}

static void su_elevate_current_task(void)
{
struct cred *new_cred = prepare_kernel_cred(NULL);

if (!new_cred) {
su_err("prepare_kernel_cred failed\n");
return;
}

new_cred->uid.val = 0;
new_cred->gid.val = 0;
new_cred->euid.val = 0;
new_cred->egid.val = 0;
new_cred->suid.val = 0;
new_cred->sgid.val = 0;
new_cred->fsuid.val = 0;
new_cred->fsgid.val = 0;

commit_creds(new_cred);
}

static bool su_is_su_client_call(struct pt_regs *regs)
{
#ifdef CONFIG_ARM64
struct filename *fname = (struct filename *)regs->regs[1];

if (!fname || !fname->name)
return false;

return strcmp(fname->name, SU_CLIENT_PATH) == 0;
#else
#error "su_hook.c's register-based argument extraction is arm64-only for now."
#endif
}

static int su_execve_pre_handler(struct kprobe *kp, struct pt_regs *regs)
{
uid_t uid;
u32 flags;

if (!su_is_su_client_call(regs))
return 0;

uid = current_uid().val;

if (su_allowlist_check(uid, &flags)) {
su_log("uid=%u has grant (flags=0x%x), elevating\n", uid, flags);
su_elevate_current_task();
} else {
su_log("uid=%u not in allowlist, denying + notifying daemon\n", uid);
su_notify_unknown_uid(uid);
}

return 0;
}

int su_hooks_install(void)
{
int ret = su_resolve_kallsyms();

if (ret < 0)
return ret;

return su_register_hook("do_execveat_common", su_execve_pre_handler);
}

void su_hooks_uninstall(void)
{
su_unregister_all_hooks();
}
