// detector_jni.cpp
//
// Connects the native GameDetector to Java. Java reports package events
// (installed / foregrounded / backgrounded); the detector decides what
// matters and calls back into Java so it can show the notifications.

#include <jni.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>

#include "game_detector.h"
#include "keymap_store.h"

using namespace gaming_keyboard;

#define LOG_TAG "DetectorJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Recursive, because a Java callback could call back into the detector.
std::recursive_mutex g_mutex;

JavaVM* g_vm = nullptr;
jobject g_java_object = nullptr;  // global reference to the GameDetectorNative object
jmethodID g_on_detected = nullptr;
jmethodID g_on_active = nullptr;
jmethodID g_on_cleared = nullptr;
std::unique_ptr<GameDetector> g_detector;

std::string ToStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return "";
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

JNIEnv* CurrentEnv() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return nullptr;
    }
    return env;
}

void ClearPendingException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// Game names go to Java as UTF-8 bytes so any character survives.
void CallWithName(jmethodID method, const std::string& name) {
    JNIEnv* env = CurrentEnv();
    if (env == nullptr || g_java_object == nullptr || method == nullptr) return;

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(name.size()));
    if (bytes == nullptr) return;
    if (!name.empty()) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(name.size()),
                                reinterpret_cast<const jbyte*>(name.data()));
    }
    env->CallVoidMethod(g_java_object, method, bytes);
    env->DeleteLocalRef(bytes);
    ClearPendingException(env);
}

void CallNoArgs(jmethodID method) {
    JNIEnv* env = CurrentEnv();
    if (env == nullptr || g_java_object == nullptr || method == nullptr) return;
    env->CallVoidMethod(g_java_object, method);
    ClearPendingException(env);
}

class JniNotifier : public NotifierInterface {
public:
    void ShowKeymapDetected(const std::string& display_name) override {
        CallWithName(g_on_detected, display_name);
    }
    void ShowOverlayActive(const std::string& display_name) override {
        CallWithName(g_on_active, display_name);
    }
    void ClearOverlayActive() override {
        CallNoArgs(g_on_cleared);
    }
};

void ReleaseLocked(JNIEnv* env) {
    g_detector.reset();
    if (g_java_object != nullptr) {
        env->DeleteGlobalRef(g_java_object);
        g_java_object = nullptr;
    }
    g_on_detected = nullptr;
    g_on_active = nullptr;
    g_on_cleared = nullptr;
}

}  // namespace

// Java class: org.blissos.gamingkeyboard.GameDetectorNative

extern "C" JNIEXPORT jboolean JNICALL
Java_org_blissos_gamingkeyboard_GameDetectorNative_nativeCreate(
        JNIEnv* env, jobject thiz, jstring keymap_dir) {
    std::string dir = ToStdString(env, keymap_dir);
    if (dir.empty()) return JNI_FALSE;

    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    ReleaseLocked(env);

    if (env->GetJavaVM(&g_vm) != JNI_OK) return JNI_FALSE;

    jclass cls = env->GetObjectClass(thiz);
    g_on_detected = env->GetMethodID(cls, "dispatchKeymapDetected", "([B)V");
    g_on_active = env->GetMethodID(cls, "dispatchOverlayActive", "([B)V");
    g_on_cleared = env->GetMethodID(cls, "dispatchOverlayCleared", "()V");
    env->DeleteLocalRef(cls);

    if (g_on_detected == nullptr || g_on_active == nullptr || g_on_cleared == nullptr) {
        LOGE("GameDetectorNative callback methods not found");
        ClearPendingException(env);
        ReleaseLocked(env);
        return JNI_FALSE;
    }

    g_java_object = env->NewGlobalRef(thiz);

    try {
        auto store = std::make_shared<KeymapStore>(dir);
        auto notifier = std::make_shared<JniNotifier>();
        g_detector = std::make_unique<GameDetector>(store, notifier);
    } catch (const std::exception& e) {
        LOGE("Detector setup failed: %s", e.what());
        ReleaseLocked(env);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_blissos_gamingkeyboard_GameDetectorNative_nativeDestroy(JNIEnv* env, jobject) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    ReleaseLocked(env);
}

extern "C" JNIEXPORT void JNICALL
Java_org_blissos_gamingkeyboard_GameDetectorNative_nativeOnPackageInstalled(
        JNIEnv* env, jobject, jstring package_name) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (g_detector) g_detector->OnPackageInstalled(ToStdString(env, package_name));
}

extern "C" JNIEXPORT void JNICALL
Java_org_blissos_gamingkeyboard_GameDetectorNative_nativeOnPackageForegrounded(
        JNIEnv* env, jobject, jstring package_name) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (g_detector) g_detector->OnPackageForegrounded(ToStdString(env, package_name));
}

extern "C" JNIEXPORT void JNICALL
Java_org_blissos_gamingkeyboard_GameDetectorNative_nativeOnPackageBackgrounded(
        JNIEnv* env, jobject, jstring package_name) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (g_detector) g_detector->OnPackageBackgrounded(ToStdString(env, package_name));
}

// The game whose keymap is currently armed, or "" if none.
extern "C" JNIEXPORT jstring JNICALL
Java_org_blissos_gamingkeyboard_GameDetectorNative_nativeCurrentArmedPackage(
        JNIEnv* env, jobject) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    std::string pkg = g_detector ? g_detector->CurrentArmedPackage() : "";
    return env->NewStringUTF(pkg.c_str());
}
