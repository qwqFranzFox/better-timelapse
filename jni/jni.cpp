#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <vector>
#include <jni.h>

#include "api/backup.hpp"

extern "C"
{
    #include "drivers/backup.h"
}

using namespace std;

/*
 * Settings-store access for com.github.qwqfranzfox.bettertimelapse.NativeStore, through the
 * same driver path OpenMemories-Tweak uses.
 *
 * The whole surface is four calls, and the rules the Java side relies on are
 * enforced here too:
 *   - read/write are size checked against the slot's real size, so a wrong assumption
 *     about a slot cannot silently write a wrong-length value;
 *   - a read-only slot is reported as its own error, not as a generic failure;
 *   - nothing in this file touches firmware, and no slot is ever written here
 *     without the Java side having asked for it by id.
 */

static void throw_native(JNIEnv *env, const char *msg)
{
    jclass c = env->FindClass("com/github/qwqfranzfox/bettertimelapse/NativeException");
    if (c) env->ThrowNew(c, msg);
}

static int prop_size(int id)
{
    int res = Backup_get_datasize(id);
    if (res <= 0) throw backup_error("Backup_get_datasize failed");
    return res;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_github_qwqfranzfox_bettertimelapse_NativeStore_read(JNIEnv *env, jclass clazz, jint id)
{
    jbyteArray arr = NULL;
    try {
        int size = prop_size(id);
        vector<char> v(size);
        int res = Backup_read(id, &v[0]);
        if (res < 0 || res != size) throw backup_error("Backup_read failed");
        arr = env->NewByteArray(size);
        env->SetByteArrayRegion(arr, 0, size, (const jbyte *) &v[0]);
    } catch (const runtime_error &e) {
        throw_native(env, e.what());
    }
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_qwqfranzfox_bettertimelapse_NativeStore_write(JNIEnv *env, jclass clazz, jint id, jbyteArray data)
{
    try {
        jsize n = env->GetArrayLength(data);
        int size = prop_size(id);
        if (n != size) throw backup_error("size mismatch");
        vector<char> v(n);
        env->GetByteArrayRegion(data, 0, n, (jbyte *) &v[0]);
        int res = Backup_write(id >> 16, id, &v[0]);
        if (res == -BACKUP_ERROR_READ_ONLY) throw backup_protected_error();
        if (res < 0 || res != size) throw backup_error("Backup_write failed");
    } catch (const runtime_error &e) {
        throw_native(env, e.what());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_qwqfranzfox_bettertimelapse_NativeStore_attr(JNIEnv *env, jclass clazz, jint id)
{
    int res = Backup_get_attribute(id);
    if (res < 0) throw_native(env, "Backup_get_attribute failed");
    return (jint) res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_qwqfranzfox_bettertimelapse_NativeStore_sync(JNIEnv *env, jclass clazz)
{
    Backup_sync_all();
}
