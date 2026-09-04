/* //device/libs/android_runtime/android_os_SystemProperties.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "SysPropJNI"

#include <utility>
#include <optional>

#include "android-base/logging.h"
#include "android-base/parsebool.h"
#include "android-base/parseint.h"
#include "android-base/properties.h"
#include "utils/misc.h"
#include <utils/Log.h>
#include "jni.h"
#include "core_jni_helpers.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>

#if defined(__BIONIC__)
# include <sys/system_properties.h>
#endif

// PrivacyKit platform-private libc exports (STEP 2): keep the per-app native
// property mirror in sync with the Java Build.* overrides. Resolved at runtime
// via dlsym rather than a link-time reference: the symbols live in libc.so
// (LIBC_PLATFORM version node) so dlsym finds them, but they are not in the libc
// link stub libandroid_runtime links against. dlsym also fails safe - a missing
// symbol just leaves the real properties in place.
#include <dlfcn.h>
namespace {
// dlopen the loaded libc and dlsym its handle: __system_property_privacykit_*
// are LIBC_PLATFORM symbols in the runtime-APEX libc namespace, which
// dlsym(RTLD_DEFAULT) does not cross from libandroid_runtime. A handle lookup
// does. RTLD_DEFAULT is kept as a fallback; a null result is a safe no-op.
void* pk_libc_sym(const char* sym) {
    static void* h = dlopen("libc.so", RTLD_NOLOAD | RTLD_NODELETE);
    void* s = h ? dlsym(h, sym) : nullptr;
    if (!s) s = dlsym(RTLD_DEFAULT, sym);
    return s;
}
void pk_prop_override(const char* name, const char* value) {
    using Fn = void (*)(const char*, const char*);
    static Fn fn = reinterpret_cast<Fn>(pk_libc_sym("__system_property_privacykit_override"));
    if (fn) fn(name, value);
}
void pk_prop_seal() {
    using Fn = void (*)();
    static Fn fn = reinterpret_cast<Fn>(pk_libc_sym("__system_property_privacykit_seal"));
    if (fn) fn();
}
}  // namespace

namespace android {
namespace {

using android::base::ParseBoolResult;

template<typename Functor>
void ReadProperty(const prop_info* prop, Functor&& functor)
{
    auto thunk = [](void* cookie,
                    const char* /*name*/,
                    const char* value,
                    uint32_t /*serial*/) {
        std::forward<Functor>(*static_cast<Functor*>(cookie))(value);
    };
    __system_property_read_callback(prop, thunk, &functor);
}

template<typename Functor>
void ReadProperty(JNIEnv* env, jstring keyJ, Functor&& functor)
{
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return;
    }
    const prop_info* prop = __system_property_find(key.c_str());
    if (!prop) {
        return;
    }
    ReadProperty(prop, std::forward<Functor>(functor));
}

jstring SystemProperties_getSS(JNIEnv* env, jclass clazz, jstring keyJ,
                               jstring defJ)
{
    jstring ret = defJ;
    ReadProperty(env, keyJ, [&](const char* value) {
        if (value[0]) {
            ret = env->NewStringUTF(value);
        }
    });
    if (ret == nullptr && !env->ExceptionCheck()) {
      ret = env->NewStringUTF("");  // Legacy behavior
    }
    return ret;
}

template <typename T>
T SystemProperties_get_integral(JNIEnv *env, jclass, jstring keyJ,
                                       T defJ)
{
    T ret = defJ;
    ReadProperty(env, keyJ, [&](const char* value) {
        android::base::ParseInt<T>(value, &ret);
    });
    return ret;
}

static jboolean jbooleanFromParseBoolResult(ParseBoolResult parseResult, jboolean defJ) {
    jboolean ret;
    switch (parseResult) {
        case ParseBoolResult::kError:
            ret = defJ;
            break;
        case ParseBoolResult::kFalse:
            ret = JNI_FALSE;
            break;
        case ParseBoolResult::kTrue:
            ret = JNI_TRUE;
            break;
    }
    return ret;
}

jboolean SystemProperties_get_boolean(JNIEnv *env, jclass, jstring keyJ,
                                      jboolean defJ)
{
    ParseBoolResult parseResult = ParseBoolResult::kError;
    ReadProperty(env, keyJ, [&](const char* value) {
        parseResult = android::base::ParseBool(value);
    });
    return jbooleanFromParseBoolResult(parseResult, defJ);
}

jlong SystemProperties_find(JNIEnv* env, jclass, jstring keyJ)
{
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return 0;
    }
    const prop_info* prop = __system_property_find(key.c_str());
    return reinterpret_cast<jlong>(prop);
}

jstring SystemProperties_getH(JNIEnv* env, jclass clazz, jlong propJ)
{
    jstring ret;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        ret = env->NewStringUTF(value);
    });
    return ret;
}

template <typename T>
T SystemProperties_get_integralH(CRITICAL_JNI_PARAMS_COMMA jlong propJ, T defJ)
{
    T ret = defJ;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        android::base::ParseInt<T>(value, &ret);
    });
    return ret;
}

jboolean SystemProperties_get_booleanH(CRITICAL_JNI_PARAMS_COMMA jlong propJ, jboolean defJ)
{
    ParseBoolResult parseResult = ParseBoolResult::kError;
    auto prop = reinterpret_cast<const prop_info*>(propJ);
    ReadProperty(prop, [&](const char* value) {
        parseResult = android::base::ParseBool(value);
    });
    return jbooleanFromParseBoolResult(parseResult, defJ);
}

void SystemProperties_set(JNIEnv *env, jobject clazz, jstring keyJ,
                          jstring valJ)
{
    ScopedUtfChars key(env, keyJ);
    if (!key.c_str()) {
        return;
    }
    std::optional<ScopedUtfChars> value;
    if (valJ != nullptr) {
        value.emplace(env, valJ);
        if (!value->c_str()) {
            return;
        }
    }
    // Calling SystemProperties.set() with a null value is equivalent to an
    // empty string, but this is not true for the underlying libc function.
    const char* value_c_str = value ? value->c_str() : "";
    // Explicitly clear errno so we can recognize __system_property_set()
    // failures from failed system calls (as opposed to "init rejected your
    // request" failures).
    errno = 0;
    bool success;
    success = !__system_property_set(key.c_str(), value_c_str);
    if (!success) {
        if (errno != 0) {
            jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                                 "failed to set system property \"%s\" to \"%s\": %m",
                                 key.c_str(), value_c_str);
        } else {
            // Must have made init unhappy, which will have logged something,
            // but there's no API to ask for more detail.
            jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                                 "failed to set system property \"%s\" to \"%s\" (check logcat for reason)",
                                 key.c_str(), value_c_str);
        }
    }
}

JavaVM* sVM = nullptr;
jclass sClazz = nullptr;
jmethodID sCallChangeCallbacks;

void do_report_sysprop_change() {
    //ALOGI("Java SystemProperties: VM=%p, Clazz=%p", sVM, sClazz);
    if (sVM != nullptr && sClazz != nullptr) {
        JNIEnv* env;
        if (sVM->GetEnv((void **)&env, JNI_VERSION_1_4) >= 0) {
            //ALOGI("Java SystemProperties: calling %p", sCallChangeCallbacks);
            env->CallStaticVoidMethod(sClazz, sCallChangeCallbacks);
            // There should not be any exceptions. But we must guarantee
            // there are none on return.
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                LOG(ERROR) << "Exception pending after sysprop_change!";
            }
        }
    }
}

void SystemProperties_add_change_callback(JNIEnv *env, jobject clazz)
{
    // This is called with the Java lock held.
    if (sVM == nullptr) {
        env->GetJavaVM(&sVM);
    }
    if (sClazz == nullptr) {
        sClazz = (jclass) env->NewGlobalRef(clazz);
        sCallChangeCallbacks = env->GetStaticMethodID(sClazz, "callChangeCallbacks", "()V");
        add_sysprop_change_callback(do_report_sysprop_change, -10000);
    }
}

void SystemProperties_report_sysprop_change(JNIEnv /**env*/, jobject /*clazz*/)
{
    report_sysprop_change();
}

void SP_setPrivacyKitOverride(JNIEnv* env, jobject clazz, jstring nameJ, jstring valueJ) {
    if (nameJ == nullptr || valueJ == nullptr) return;
    ScopedUtfChars name(env, nameJ);
    ScopedUtfChars value(env, valueJ);
    if (!name.c_str() || !value.c_str()) {
        return;
    }
    pk_prop_override(name.c_str(), value.c_str());
}

void SP_sealPrivacyKitOverrides(JNIEnv*, jobject) {
    pk_prop_seal();
}

}  // namespace

int register_android_os_SystemProperties(JNIEnv *env)
{
    const JNINativeMethod method_table[] = {
        { "native_get",
          "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
          (void*) SystemProperties_getSS },
        { "native_get_int", "(Ljava/lang/String;I)I",
          (void*) SystemProperties_get_integral<jint> },
        { "native_get_long", "(Ljava/lang/String;J)J",
          (void*) SystemProperties_get_integral<jlong> },
        { "native_get_boolean", "(Ljava/lang/String;Z)Z",
          (void*) SystemProperties_get_boolean },
        { "native_find",
          "(Ljava/lang/String;)J",
          (void*) SystemProperties_find },
        { "native_get",
          "(J)Ljava/lang/String;",
          (void*) SystemProperties_getH },
        { "native_get_int", "(JI)I",
          (void*) SystemProperties_get_integralH<jint> },
        { "native_get_long", "(JJ)J",
          (void*) SystemProperties_get_integralH<jlong> },
        { "native_get_boolean", "(JZ)Z",
          (void*) SystemProperties_get_booleanH },
        { "native_set", "(Ljava/lang/String;Ljava/lang/String;)V",
          (void*) SystemProperties_set },
        { "native_add_change_callback", "()V",
          (void*) SystemProperties_add_change_callback },
        { "native_report_sysprop_change", "()V",
          (void*) SystemProperties_report_sysprop_change },
        { "native_pk_override", "(Ljava/lang/String;Ljava/lang/String;)V",
          (void*) SP_setPrivacyKitOverride },
        { "native_pk_seal", "()V",
          (void*) SP_sealPrivacyKitOverrides },
    };
    return RegisterMethodsOrDie(env, "android/os/SystemProperties",
                                method_table, NELEM(method_table));
}

}  // namespace android
