/*
 * Copyright (C) 2012 Steven Luo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "process.h"

#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <dirent.h>
#include <cerrno>
#include <fcntl.h>
#include <cstdlib>
#include <unistd.h>
#include <termios.h>
#include <csignal>
#include <cstring>



static int throwOutOfMemoryError(JNIEnv *env, const char *message)
{
    jclass exClass;
    const char *className = "java/lang/OutOfMemoryError";

    exClass = env->FindClass(className);
    return env->ThrowNew(exClass, message);
}

static int throwIOException(JNIEnv *env, int errnum, const char *message)
{
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s errno %s(%d)",
        message, strerror(errno), errno);

    if (errnum != 0) {
        const char *s = strerror(errnum);
        if (strcmp(s, "Unknown error") != 0)
            message = s;
    }

    jclass exClass;
    const char *className = "java/io/IOException";

    exClass = env->FindClass(className);
    return env->ThrowNew(exClass, message);
}

static void closeNonstandardFileDescriptors() {
    // Android uses shared memory to communicate between processes. The file descriptor is passed
    // to child processes using the environment variable ANDROID_PROPERTY_WORKSPACE, which is of
    // the form "properties_fd,sizeOfSharedMemory"
    int properties_fd = -1;
    char* properties_fd_string = getenv("ANDROID_PROPERTY_WORKSPACE");
    if (properties_fd_string != nullptr) {
        properties_fd = (int) strtol(properties_fd_string, nullptr, 10);
    }
    DIR *dir = opendir("/proc/self/fd");
    if(dir != nullptr) {
        int dir_fd = dirfd(dir);

        while(true) {
            struct dirent *entry = readdir(dir);
            if(entry == nullptr) {
                break;
            }

            int fd = (int) strtol(entry->d_name, nullptr, 10);
            if(fd > STDERR_FILENO && fd != dir_fd && fd != properties_fd) {
                close(fd);
            }
        }

        closedir(dir);
    }
}

static int create_subprocess(JNIEnv *env, const char *cmd, char *const argv[], char *const envp[], int masterFd)
{
    // same size as Android 1.6 libc/unistd/ptsname_r.c
    char devname[64];
    pid_t pid;

    fcntl(masterFd, F_SETFD, FD_CLOEXEC);

    // grantpt is unnecessary, because we already assume devpts by using /dev/ptmx
    if(unlockpt(masterFd)){
        throwIOException(env, errno, "trouble with /dev/ptmx");
        return -1;
    }
    memset(devname, 0, sizeof(devname));
    // Early (Android 1.6) bionic versions of ptsname_r had a bug where they returned the buffer
    // instead of 0 on success.  A compatible way of telling whether ptsname_r
    // succeeded is to zero out errno and check it after the call
    errno = 0;
    int ptsResult = ptsname_r(masterFd, devname, sizeof(devname));
    if (ptsResult && errno) {
        throwIOException(env, errno, "ptsname_r returned error");
        return -1;
    }

    pid = fork();
    if(pid < 0) {
        throwIOException(env, errno, "fork failed");
        return -1;
    }

    if(pid == 0){
        int pts;

        setsid();

        pts = open(devname, O_RDWR);
        if(pts < 0) exit(-1);

        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        closeNonstandardFileDescriptors();

        if (envp) {
            for (; *envp; ++envp) {
                putenv(*envp);
            }
        }

        execv(cmd, argv);
        exit(-1);
    } else {
        return (int) pid;
    }
}

extern "C" {

JNIEXPORT void JNICALL Java_jackpal_androidterm_TermExec_sendSignal(JNIEnv *env, jclass clazz,
    jint procId, jint signal)
{
    kill(procId, signal);
}

JNIEXPORT jint JNICALL Java_jackpal_androidterm_TermExec_waitFor(JNIEnv *env, jclass clazz, jint procId) {
    int status;
    waitpid(procId, &status, 0);
    int result = 0;
    if (WIFEXITED(status)) {
        result = WEXITSTATUS(status);
    }
    return result;
}

JNIEXPORT jint JNICALL Java_jackpal_androidterm_TermExec_createSubprocessInternal(JNIEnv *env, jclass clazz,
    jstring cmd, jobjectArray args, jobjectArray envVars, jint masterFd)
{
    int ptm = 0;
    const char *cmd_str = cmd ? env->GetStringUTFChars(cmd, nullptr) : nullptr;

    jsize size = args ? env->GetArrayLength(args) : 0;
    char **argv = nullptr;
    char **envp = nullptr;
    if (size > 0) {
        argv = (char **)malloc((size+1)*sizeof(char *));
        if (!argv) {
            throwOutOfMemoryError(env, "Couldn't allocate argv array");
            goto cleanup;
        }
        for (int i = 0; i < size; ++i) {
            auto arg = reinterpret_cast<jstring>(env->GetObjectArrayElement(args, i));
            const char *arg_str = arg ? env->GetStringUTFChars(arg, nullptr) : nullptr;
            if (!arg_str) {
                if (arg) env->DeleteLocalRef(arg);
                throwOutOfMemoryError(env, "Couldn't get argument from array");
                argv[i] = nullptr;
                goto cleanup;
            }
            argv[i] = strdup(arg_str);
            env->ReleaseStringUTFChars(arg, arg_str);
            env->DeleteLocalRef(arg);
            if (!argv[i]) {
                throwOutOfMemoryError(env, "Couldn't allocate argument string");
                goto cleanup;
            }
        }
        argv[size] = nullptr;
    }

    size = envVars ? env->GetArrayLength(envVars) : 0;
    if (size > 0) {
        envp = (char **)malloc((size+1)*sizeof(char *));
        if (!envp) {
            throwOutOfMemoryError(env, "Couldn't allocate envp array");
            goto cleanup;
        }
        for (int i = 0; i < size; ++i) {
            auto var = reinterpret_cast<jstring>(env->GetObjectArrayElement(envVars, i));
            const char *var_str = var ? env->GetStringUTFChars(var, nullptr) : nullptr;
            if (!var_str) {
                if (var) env->DeleteLocalRef(var);
                throwOutOfMemoryError(env, "Couldn't get env var from array");
                envp[i] = nullptr;
                goto cleanup;
            }
            envp[i] = strdup(var_str);
            env->ReleaseStringUTFChars(var, var_str);
            env->DeleteLocalRef(var);
            if (!envp[i]) {
                throwOutOfMemoryError(env, "Couldn't allocate env var string");
                goto cleanup;
            }
        }
        envp[size] = nullptr;
    }

    ptm = create_subprocess(env, cmd_str, argv, envp, masterFd);

cleanup:
    if (cmd) env->ReleaseStringUTFChars(cmd, cmd_str);

    if (argv) {
        for (char **tmp = argv; *tmp; ++tmp) free(*tmp);
        free(argv);
    }
    if (envp) {
        for (char **tmp = envp; *tmp; ++tmp) free(*tmp);
        free(envp);
    }

    return ptm;
}

}
