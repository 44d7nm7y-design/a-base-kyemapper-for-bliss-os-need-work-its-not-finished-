#include <jni.h>
#include <string>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <cstring>
#include <cerrno>
#include <sys/time.h>
#include <cstdint>

#define LOG_TAG "NativeInputBridge_CPP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int device_fd = -1;

/**
 * Helper function to send a raw struct input_event to the open kernel device descriptor.
 */
static bool write_event(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    
    // Get current system time for event timestamp
    gettimeofday(&ev.time, NULL);
    ev.type = type;
    ev.code = code;
    ev.value = value;

    if (write(fd, &ev, sizeof(ev)) < 0) {
        LOGE("Failed to write input_event to device: %s", strerror(errno));
        return false;
    }
    return true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_xtr_keymapper_input_NativeInputBridge_initNativeDevice(
        JNIEnv *env,
        jobject thiz,
        jstring device_path) {

    const char *path = env->GetStringUTFChars(device_path, nullptr);
    if (path == nullptr) {
        LOGE("Device path string conversion failed.");
        return JNI_FALSE;
    }

    LOGI("Opening native device interface at: %s", path);
    device_fd = open(path, O_WRONLY | O_NONBLOCK);
    
    env->ReleaseStringUTFChars(device_path, path);

    if (device_fd < 0) {
        LOGE("Failed to open device node: %s", strerror(errno));
        return JNI_FALSE;
    }

    LOGI("Native device successfully initialized with fd: %d", device_fd);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_xtr_keymapper_input_NativeInputBridge_sendKeyEvent(
        JNIEnv *env,
        jobject thiz,
        jint scan_code,
        jint value) {

    if (device_fd < 0) {
        LOGE("Cannot send key event: device descriptor is not open.");
        return JNI_FALSE;
    }

    // Write EV_KEY (Key press/release event)
    if (!write_event(device_fd, EV_KEY, static_cast<uint16_t>(scan_code), static_cast<int32_t>(value))) {
        return JNI_FALSE;
    }

    // Write EV_SYN (Synchronization signal required by Linux input subsystem)
    if (!write_event(device_fd, EV_SYN, SYN_REPORT, 0)) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_xtr_keymapper_input_NativeInputBridge_closeNativeDevice(
        JNIEnv *env,
        jobject thiz) {

    if (device_fd >= 0) {
        close(device_fd);
        LOGI("Native device descriptor %d closed.", device_fd);
        device_fd = -1;
    }
}