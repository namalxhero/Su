/*
 * su_main.c - module entry point.
 *
 * Wires together: allowlist cache init, device node (ioctl channel),
 * the hook framework (execve interception), and - as of Phase 5 - path
 * visibility (su_hide.c) and per-task mount isolation
 * (su_mount_isolate.c). Init order matters here: su_mount_isolate_init()
 * must run before su_hooks_install() activates the execve hook that
 * calls su_mount_isolate_check() on every exec, and it must run before
 * Phase 4's boot trigger has any chance to apply module mounts, so the
 * "clean" mount namespace it snapshots actually predates them.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include "su_common.h"

static int __init su_module_init(void)
{
	int ret;

	su_log("loading (Phase 5 skeleton build)\n");

	ret = su_allowlist_init();
	if (ret < 0)
		return ret;

	ret = su_ioctl_init();
	if (ret < 0)
		goto err_allowlist;

	ret = su_hide_init();
	if (ret < 0)
		goto err_ioctl;

	ret = su_mount_isolate_init();
	if (ret < 0)
		goto err_hide;

	ret = su_hooks_install_hide();
	if (ret < 0)
		goto err_mount_isolate;

	/*
	 * Execve hook last: su_execve_pre_handler() (su_hook.c) calls into
	 * su_boot_trigger_check() and su_mount_isolate_check() on every
	 * exec from the moment this is installed, so everything those
	 * depend on must already be initialised.
	 */
	ret = su_hooks_install();
	if (ret < 0)
		goto err_hooks_hide;

	su_log("loaded\n");
	return 0;

err_hooks_hide:
	su_hooks_uninstall_hide();
err_mount_isolate:
	su_mount_isolate_exit();
err_hide:
	su_hide_exit();
err_ioctl:
	su_ioctl_exit();
err_allowlist:
	su_allowlist_exit();
	return ret;
}

static void __exit su_module_exit(void)
{
	/* Reverse of init order: execve hook first, so nothing can call
	 * into su_mount_isolate_check()/su_boot_trigger_check() while the
	 * things they depend on are being torn down. */
	su_hooks_uninstall();
	su_hooks_uninstall_hide();
	su_mount_isolate_exit();
	su_hide_exit();
	su_ioctl_exit();
	su_allowlist_exit();
	su_log("unloaded\n");
}

module_init(su_module_init);
module_exit(su_module_exit);

/*
 * GPL license required: commit_creds()/prepare_kernel_cred() and several
 * other symbols we rely on are GPL-exported (EXPORT_SYMBOL_GPL), so a
 * non-GPL MODULE_LICENSE would fail to link against them at load time
 * regardless of what we write here.
 */
MODULE_LICENSE("GPL");
MODULE_AUTHOR("namalxhero");
MODULE_DESCRIPTION("Universal root kernel module (su + Magisk-module-support + visibility control) - Phase 5 skeleton");
MODULE_VERSION("0.0.3-phase5");
