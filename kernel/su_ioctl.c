/*
 * su_ioctl.c - /dev/su_ctl character device: the kernel <-> manager-app
 * daemon communication channel (see include/su_uapi.h for the wire
 * format/commands).
 */

#include <linux/module.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>
#include <linux/uaccess.h>
#include <linux/wait.h>
#include <linux/version.h>
#include "su_common.h"
#include "su_uapi.h"

#define SU_API_VERSION 1

static dev_t su_devno;
static struct cdev su_cdev;
static struct class *su_class;
static struct device *su_device;

/* TODO(Phase 5): restrict who may open this node - right now permissions
 * come from the device node mode plus whatever group the manager app
 * daemon runs as; revisit with a proper capability/SELinux check once
 * the manager app's process identity is finalised. */

static int su_dev_open(struct inode *inode, struct file *filp)
{
	return 0;
}

static int su_dev_release(struct inode *inode, struct file *filp)
{
	return 0;
}

static long su_dev_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
	void __user *uarg = (void __user *)arg;

	switch (cmd) {
	case SU_IOC_UID_GRANT: {
		struct su_uid_entry entry;

		if (copy_from_user(&entry, uarg, sizeof(entry)))
			return -EFAULT;
		return su_allowlist_grant(entry.uid, entry.flags);
	}

	case SU_IOC_UID_REVOKE: {
		struct su_uid_entry entry;

		if (copy_from_user(&entry, uarg, sizeof(entry)))
			return -EFAULT;
		return su_allowlist_revoke(entry.uid);
	}

	case SU_IOC_UID_QUERY: {
		struct su_uid_entry entry;
		u32 flags = 0;

		if (copy_from_user(&entry, uarg, sizeof(entry)))
			return -EFAULT;

		entry.flags = su_allowlist_check(entry.uid, &flags) ? flags : SU_GRANT_NONE;

		if (copy_to_user(uarg, &entry, sizeof(entry)))
			return -EFAULT;
		return 0;
	}

	case SU_IOC_GET_VERSION: {
		struct su_version_info info = {
			.api_version = SU_API_VERSION,
			.max_allowlist = SU_MAX_ALLOWLIST,
		};

		if (copy_to_user(uarg, &info, sizeof(info)))
			return -EFAULT;
		return 0;
	}

	default:
		return -ENOTTY;
	}
}

/*
 * Blocking read: the daemon calls read() and sleeps here until an unknown
 * UID triggers su_notify_unknown_uid(). Returns one su_uid_entry per read.
 * Simple single-slot model for Phase 2 - revisit with a real queue if
 * concurrent unknown-UID requests turn out to matter in practice.
 */
static ssize_t su_dev_read(struct file *filp, char __user *buf, size_t len, loff_t *off)
{
	struct su_uid_entry entry;
	uid_t uid;
	int ret;

	if (len < sizeof(entry))
		return -EINVAL;

	ret = wait_event_interruptible(su_request_waitq, su_pop_pending_request(&uid));
	if (ret)
		return ret;

	entry.uid = uid;
	entry.flags = SU_GRANT_NONE;

	if (copy_to_user(buf, &entry, sizeof(entry)))
		return -EFAULT;

	return sizeof(entry);
}

static const struct file_operations su_fops = {
	.owner          = THIS_MODULE,
	.open           = su_dev_open,
	.release        = su_dev_release,
	.unlocked_ioctl = su_dev_ioctl,
	.read           = su_dev_read,
};

int su_ioctl_init(void)
{
	int ret;

	ret = alloc_chrdev_region(&su_devno, 0, 1, SU_DEVICE_NAME);
	if (ret < 0) {
		su_err("alloc_chrdev_region failed (ret=%d)\n", ret);
		return ret;
	}

	cdev_init(&su_cdev, &su_fops);
	su_cdev.owner = THIS_MODULE;

	ret = cdev_add(&su_cdev, su_devno, 1);
	if (ret < 0) {
		su_err("cdev_add failed (ret=%d)\n", ret);
		goto err_unregister;
	}

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 4, 0)
	su_class = class_create(SU_DEVICE_NAME);
#else
	su_class = class_create(THIS_MODULE, SU_DEVICE_NAME);
#endif
	if (IS_ERR(su_class)) {
		ret = PTR_ERR(su_class);
		su_err("class_create failed (ret=%d)\n", ret);
		goto err_cdev;
	}

	su_device = device_create(su_class, NULL, su_devno, NULL, SU_DEVICE_NAME);
	if (IS_ERR(su_device)) {
		ret = PTR_ERR(su_device);
		su_err("device_create failed (ret=%d)\n", ret);
		goto err_class;
	}

	su_log("device node %s ready\n", SU_DEVICE_PATH);
	return 0;

err_class:
	class_destroy(su_class);
err_cdev:
	cdev_del(&su_cdev);
err_unregister:
	unregister_chrdev_region(su_devno, 1);
	return ret;
}

void su_ioctl_exit(void)
{
	device_destroy(su_class, su_devno);
	class_destroy(su_class);
	cdev_del(&su_cdev);
	unregister_chrdev_region(su_devno, 1);
}
