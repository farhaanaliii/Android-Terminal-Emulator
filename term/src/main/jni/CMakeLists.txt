cmake_minimum_required(VERSION 3.22.1)

project("jackpal-androidterm5")

add_library(jackpal-androidterm5 SHARED
        common.cpp
        termExec.cpp
        fileCompat.cpp)

find_library(log-lib log)

target_link_libraries(jackpal-androidterm5
        ${log-lib})

target_link_options(jackpal-androidterm5 PRIVATE "-Wl,-z,max-page-size=16384")

# BusyBox, for the app's built-in shell environment. BusyBox is fetched
# from its upstream git repository at a pinned tag and compiled with its
# own Kbuild, cross-compiled by the same NDK toolchain that builds this
# project (the Kbuild is driven with a CROSS_COMPILE prefix pointing at
# the NDK clang). The build produces a plain executable named "busybox";
# env/package_env.cmake then packages it, together with the applet
# symlink list and (on x86_64) the NDK sysroot headers/libraries, into a
# per-ABI environment asset zip that the app extracts into its files/usr
# prefix on first launch. See env/README.md for the BusyBox version, the
# source repository and tag, and the license; the exact build
# configuration is env/busybox-1.37.0.config, produced by defconfig plus
# env/busybox.config.fragment.
include(ExternalProject)
include(ProcessorCount)

if(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "arm64-v8a")
    if(ANDROID_ABI STREQUAL "x86_64")
        set(BUSYBOX_TRIPLE "x86_64-linux-android")
        set(BUSYBOX_ARCH "x86_64")
    else()
        set(BUSYBOX_TRIPLE "aarch64-linux-android")
        set(BUSYBOX_ARCH "aarch64")
    endif()
    string(REPLACE "android-" "" BUSYBOX_API "${ANDROID_PLATFORM}")
    set(BUSYBOX_LLVM_BIN "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin")
    set(BUSYBOX_TOOLS "${CMAKE_CURRENT_BINARY_DIR}/busybox-tools")
    set(BUSYBOX_PREFIX "${BUSYBOX_TOOLS}/${BUSYBOX_TRIPLE}${BUSYBOX_API}-")
    file(MAKE_DIRECTORY "${BUSYBOX_TOOLS}")
    # Kbuild invokes $(CROSS_COMPILE)gcc/cc/as. The NDK clang entry points
    # are wrapper scripts that find the real clang relative to their own
    # path, so they cannot be symlinked under a different name; generate
    # tiny exec wrappers instead. The LLVM binutils are plain binaries and
    # are symlinked.
    foreach(tool gcc cc as)
        file(WRITE "${BUSYBOX_PREFIX}${tool}" "#!/bin/sh\nexec \"${BUSYBOX_LLVM_BIN}/${BUSYBOX_TRIPLE}${BUSYBOX_API}-clang\" \"$@\"\n")
        file(CHMOD "${BUSYBOX_PREFIX}${tool}" PERMISSIONS
            OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
    endforeach()
    foreach(pair "ld.lld:ld" "llvm-ar:ar" "llvm-nm:nm" "llvm-ranlib:ranlib" "llvm-strip:strip" "llvm-objcopy:objcopy" "llvm-objdump:objdump")
        string(REPLACE ":" ";" pair_list "${pair}")
        list(GET pair_list 0 pair_source)
        list(GET pair_list 1 pair_target)
        execute_process(COMMAND ${CMAKE_COMMAND} -E create_symlink
            "${BUSYBOX_LLVM_BIN}/${pair_source}" "${BUSYBOX_PREFIX}${pair_target}")
    endforeach()
    find_program(BUSYBOX_MAKE NAMES make HINTS "${ANDROID_NDK}/prebuilt/linux-x86_64/bin" REQUIRED)
    ProcessorCount(BUSYBOX_JOBS)
    if(BUSYBOX_JOBS EQUAL 0)
        set(BUSYBOX_JOBS 4)
    endif()
    get_filename_component(BUSYBOX_ENV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../env" ABSOLUTE)
    if(NOT DEFINED ENV_ASSETS_DIR)
        set(ENV_ASSETS_DIR "${CMAKE_CURRENT_BINARY_DIR}/env-assets")
    endif()
    # Tools built from upstream source into the same environment (bash,
    # and the OpenSSH clients below) install into ENV_TOOLS_DIR;
    # env/package_env.cmake merges that tree into the environment asset
    # zip during the busybox install step, which is why every tool
    # project is a dependency of busybox_external.
    set(ENV_TOOLS_DIR "${CMAKE_CURRENT_BINARY_DIR}/env-tools")
    set(ENV_DEVICE_PREFIX "/data/user/0/jackpal.androidterm/files/usr")
    file(MAKE_DIRECTORY "${ENV_TOOLS_DIR}/bin")
    # The silentoldconfig step in CONFIGURE_COMMAND materializes
    # autoconf.h before the build starts: on a first build otherwise,
    # Kbuild regenerates it mid-build and Makefile.flags evaluates the
    # library list (e.g. -lresolv) against the pre-generation state.
    ExternalProject_Add(busybox_external
        GIT_REPOSITORY https://git.busybox.net/busybox
        GIT_TAG 1_37_0
        GIT_SHALLOW TRUE
        UPDATE_COMMAND ""
        CONFIGURE_COMMAND "${BUSYBOX_MAKE}" -C <SOURCE_DIR> O=<BINARY_DIR> defconfig
        COMMAND ${CMAKE_COMMAND} -DBUSYBOX_CONFIG=<BINARY_DIR>/.config -DFRAGMENT=${BUSYBOX_ENV_DIR}/busybox.config.fragment -P ${BUSYBOX_ENV_DIR}/apply_busybox_config.cmake
        COMMAND "${BUSYBOX_MAKE}" -C <SOURCE_DIR> O=<BINARY_DIR> silentoldconfig
        BUILD_COMMAND "${BUSYBOX_MAKE}" -C <SOURCE_DIR> O=<BINARY_DIR> CROSS_COMPILE=${BUSYBOX_PREFIX} -j${BUSYBOX_JOBS}
        INSTALL_COMMAND "${BUSYBOX_MAKE}" -C <SOURCE_DIR> O=<BINARY_DIR> CROSS_COMPILE=${BUSYBOX_PREFIX} install CONFIG_PREFIX=<BINARY_DIR>/bb_install_root
        COMMAND ${CMAKE_COMMAND} -DBUSYBOX_BIN=<BINARY_DIR>/busybox -DBUSYBOX_LINKS=<BINARY_DIR>/busybox.links -DSTAGE_DIR=<BINARY_DIR>/env-stage -DENV_DIR=${BUSYBOX_ENV_DIR} -DANDROID_ABI=${ANDROID_ABI} -DTRIPLE=${BUSYBOX_TRIPLE} -DAPI=${BUSYBOX_API} -DNDK_SYSROOT=${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot -DTOOLS_DIR=${ENV_TOOLS_DIR} -DOUT_ZIP=${ENV_ASSETS_DIR}/busybox-${BUSYBOX_ARCH}.zip -P ${BUSYBOX_ENV_DIR}/package_env.cmake
        BUILD_BYPRODUCTS "${ENV_ASSETS_DIR}/busybox-${BUSYBOX_ARCH}.zip"
    )
    add_dependencies(jackpal-androidterm5 busybox_external)

    # GNU bash, from the upstream release tarball, cross-compiled with
    # the NDK clang at API ${BUSYBOX_API} like everything else in the
    # environment. Android has no termcap library, so bash falls back
    # to its bundled libtermcap; its bundled readline is used as well.
    # The two bash_cv_* cache variables are the standard answers for
    # cross builds (configure cannot run its tests): /dev/fd works on
    # Android, and bionic's getcwd allocates its buffer. The binary is
    # self-contained (libc/libdl only), so the install step is a copy.
    ExternalProject_Add(bash_external
        URL https://ftp.gnu.org/gnu/bash/bash-5.3.tar.gz
        URL_HASH SHA256=0d5cd86965f869a26cf64f4b71be7b96f90a3ba8b3d74e27e8e9d9d5550f31ba
        CONFIGURE_COMMAND env CC=${BUSYBOX_LLVM_BIN}/${BUSYBOX_TRIPLE}${BUSYBOX_API}-clang AR=${BUSYBOX_LLVM_BIN}/llvm-ar RANLIB=${BUSYBOX_LLVM_BIN}/llvm-ranlib
            <SOURCE_DIR>/configure --host=${BUSYBOX_TRIPLE} --prefix=${ENV_DEVICE_PREFIX}
            --without-bash-malloc --disable-nls bash_cv_dev_fd=standard bash_cv_getcwd_malloc=yes
        BUILD_COMMAND env PATH=${ANDROID_NDK}/prebuilt/linux-x86_64/bin:$ENV{PATH} "${BUSYBOX_MAKE}" -j${BUSYBOX_JOBS}
        INSTALL_COMMAND ${CMAKE_COMMAND} -E copy <BINARY_DIR>/bash ${ENV_TOOLS_DIR}/bin/bash
        BUILD_BYPRODUCTS "${ENV_TOOLS_DIR}/bin/bash"
    )
    add_dependencies(busybox_external bash_external)
endif()
