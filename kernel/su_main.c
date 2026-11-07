/*
 * su_main.c - module entry point.
 *
 * Wires together: allowlist cache init, device node (ioctl channel), and
 * the hook framework (execve interception, with path-hiding hooks to be
 * added in a later phase through the same su_register_hook() API).
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include "su_common.h"

static int __init su_module_init(void)
{
	int ret;

	su_log("loading (Phase 2 skeleton build)\n");

	ret = su_allowlist_init();
	if (ret < 0)
		return ret;

	ret = su_ioctl_init();
	if (ret < 0)
		goto err_allowlist;

	ret = su_hooks_install();
	if (ret < 0)
		goto err_ioctl;

	su_log("loaded\n");
	return 0;

err_ioctl:
	su_ioctl_exit();
err_allowlist:
	su_allowlist_exit();
	return ret;
}

static void __exit su_module_exit(void)
{
	su_hooks_uninstall();
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
MODULE_DESCRIPTION("Universal root kernel module (su + Magisk-module-support) - Phase 2 skeleton");
MODULE_VERSION("0.0.1-phase2");
