/*
 * su_native.cpp - JNI bridge: Kotlin (SuNative.kt) <-> /dev/su_ctl.
 *
 * This file owns the open()/ioctl()/read()/close() calls against the
 * kernel module's character device. The struct layout and ioctl command
 * numbers come straight from kernel/include/su_uapi.h (see CMakeLists.txt's
 * include path) - that header is the single source of truth for this ABI,
 * and is no longer duplicated here by hand.
 */

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <cerrno>
#include <cstdint>
#include <cstring>

#include "su_uapi.h"

#define LOG_TAG "SuNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static int open_su_device() {
    int fd = open(SU_DEVICE_PATH, O_RDWR);
    if (fd < 0) {
        LOGE("open(%s) failed: %s", SU_DEVICE_PATH, strerror(errno));
    }
    return fd;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_namalxhero_su_native_SuNative_grantUid(JNIEnv *, jobject, jint uid, jint flags) {
    int fd = open_su_device();
    if (fd < 0) return -1;

    su_uid_entry entry{};
    entry.uid = static_cast<__u32>(uid);
    entry.flags = static_cast<__u32>(flags);

    int ret = ioctl(fd, SU_IOC_UID_GRANT, &entry);
    if (ret < 0) {
        LOGE("SU_IOC_UID_GRANT failed for uid=%d: %s", uid, strerror(errno));
    }
    close(fd);
    return ret;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_namalxhero_su_native_SuNative_revokeUid(JNIEnv *, jobject, jint uid) {
    int fd = open_su_device();
    if (fd < 0) return -1;

    su_uid_entry entry{};
    entry.uid = static_cast<__u32>(uid);

    int ret = ioctl(fd, SU_IOC_UID_REVOKE, &entry);
    if (ret < 0) {
        LOGE("SU_IOC_UID_REVOKE failed for uid=%d: %s", uid, strerror(errno));
    }
    close(fd);
    return ret;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_namalxhero_su_native_SuNative_queryUid(JNIEnv *, jobject, jint uid) {
    int fd = open_su_device();
    if (fd < 0) return -1;

    su_uid_entry entry{};
    entry.uid = static_cast<__u32>(uid);
    entry.flags = SU_GRANT_NONE;

    int ret = ioctl(fd, SU_IOC_UID_QUERY, &entry);
    close(fd);

    if (ret < 0) {
        LOGE("SU_IOC_UID_QUERY failed for uid=%d: %s", uid, strerror(errno));
        return -1;
    }
    return static_cast<jint>(entry.flags);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_namalxhero_su_native_SuNative_getVersionInfo(JNIEnv *env, jobject) {
    jintArray result = env->NewIntArray(2);
    int fd = open_su_device();
    if (fd < 0) {
        jint fail[2] = {-1, -1};
        env->SetIntArrayRegion(result, 0, 2, fail);
        return result;
    }

    su_version_info info{};
    int ret = ioctl(fd, SU_IOC_GET_VERSION, &info);
    close(fd);

    jint values[2] = {-1, -1};
    if (ret == 0) {
        values[0] = static_cast<jint>(info.api_version);
        values[1] = static_cast<jint>(info.max_allowlist);
    } else {
        LOGE("SU_IOC_GET_VERSION failed: %s", strerror(errno));
    }
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

/*
 * Blocking read(): see su_ioctl.c's su_dev_read(). This call parks the
 * calling thread in the kernel until su_notify_unknown_uid() wakes it up
 * (or the fd is closed / the module is unloaded, in which case read()
 * returns an error and we surface -1).
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_namalxhero_su_native_SuNative_waitForPendingRequest(JNIEnv *, jobject) {
    int fd = open_su_device();
    if (fd < 0) return -1;

    su_uid_entry entry{};
    ssize_t n = read(fd, &entry, sizeof(entry));
    close(fd);

    if (n != static_cast<ssize_t>(sizeof(entry))) {
        LOGE("read(%s) returned %zd (errno=%s)", SU_DEVICE_PATH, n, strerror(errno));
        return -1;
    }
    return static_cast<jint>(entry.uid);
}
