#!/usr/bin/env bash
# Usage: scripts/build.sh [android|benchmark|release] [arm64-v8a|x86_64]
#   android    debug APK: android/app/build/outputs/apk/debug/app-debug.apk
#   benchmark  non-debuggable, debug-signed APK:
#              android/app/build/outputs/apk/benchmark/app-benchmark.apk
#   release    release-signed APK: android/app/build/outputs/apk/release/app-release.apk
#              Set MIEL_SIGNING_STORE_FILE, MIEL_SIGNING_STORE_PASSWORD,
#              MIEL_SIGNING_KEY_ALIAS and MIEL_SIGNING_KEY_PASSWORD.
# Set MIEL_VERSION_NAME to the release's MAJOR.MINOR.PATCH version.
# Needs ANDROID_HOME (platform 37, NDK 27.2.12479018; arm64 also the NDK and CMake named in
# third_party/*/manifest.json) and JAVA_HOME (JDK 17 or 21).
set -euo pipefail
cd "$(dirname "$0")/.."
target=${1:-android}
abi=${2:-arm64-v8a}
die() {
    echo "$*" >&2
    exit 1
}

case $target in
    android) variant=debug task=assembleDebug ;;
    benchmark) variant=benchmark task=assembleBenchmark ;;
    release) variant=release task=assembleRelease ;;
    *) die "Unknown target: $target" ;;
esac
case $abi in
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
    *) die "Unknown ABI: $abi" ;;
esac
case $(uname -s) in
    Darwin) host=darwin-x86_64 ;;
    Linux) host=linux-x86_64 ;;
    *) die "Unsupported host: $(uname -s)" ;;
esac
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK with platform 37}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 or 21}"
gradle_args=(--console=plain)
if [ -n "${MIEL_VERSION_NAME:-}" ]; then
    gradle_args+=("-PmielVersionName=$MIEL_VERSION_NAME")
fi
[ -f "$ANDROID_HOME/platforms/android-37.0/android.jar" ] || die "Install SDK platform 37"
ndk=$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/$host/bin
[ -x "$ndk/${triple}29-clang" ] || die "Install NDK 27.2.12479018"

if [ "$target" = release ]; then
    android/gradlew -p android :app:validateSigningRelease "${gradle_args[@]}"
fi

jni=android/app/src/$variant/jniLibs/$abi
rm -rf "$jni"
mkdir -p "$jni"
flags=(-std=c++20 -O2 -g -fPIC -shared -static-libstdc++ -Wl,-z,max-page-size=16384 -Ithird_party/libretro)
"$ndk/${triple}29-clang++" "${flags[@]}" android/core/host.cpp android/core/vulkan.cpp \
    -lvulkan -landroid -lEGL -lGLESv3 -laaudio -llog -ldl -o "$jni/libmiel_core.so"
if [ "$target" != release ]; then
    "$ndk/${triple}29-clang++" "${flags[@]}" android/core/test_core.cpp -lGLESv3 -o "$jni/libmiel_testcore.so"
fi

# Builds the libretro core in third_party/<name> for arm64 from the source archive its manifest pins,
# with its patches applied, and packages <output>.so as lib<package>.so. The build is redone when the
# archive or the patches change. Further arguments go to CMake.
core_sources=()
core() {
    local name=$1 output=$2 package=$3
    shift 3
    local manifest=third_party/$name/manifest.json patches
    field() { sed -n "s/.*\"$1\": \"\(.*\)\".*/\1/p" "$manifest"; }
    patches=$(ls "third_party/$name/patches/"*.patch 2>/dev/null || true)
    local url core_ndk cmake_bin src built
    url=$(field source_url)
    core_ndk=$ANDROID_HOME/ndk/$(field android_ndk)
    cmake_bin=$ANDROID_HOME/cmake/$(field android_cmake)/bin
    [ -f "$core_ndk/build/cmake/android.toolchain.cmake" ] || die "Install NDK $(field android_ndk)"
    if [ "$(field build_system)" != ndk ]; then
        [ -x "$cmake_bin/cmake" ] || die "Install SDK CMake $(field android_cmake)"
    fi
    src=$PWD/.tools/$name/src-$({
        field source_sha256
        cat $patches /dev/null
    } | shasum -a 256 | cut -c1-12)
    core_sources+=("$src")
    built=$(find "$src/build" -name "$output.so" 2>/dev/null | head -1 || true)
    if [ -z "$built" ]; then
        local tarball=.tools/$name/$(basename "$url")
        mkdir -p ".tools/$name"
        [ -f "$tarball" ] || curl -fL -o "$tarball" "$url"
        echo "$(field source_sha256)  $tarball" | shasum -a 256 -c - >/dev/null ||
            die "$name source hash mismatch"
        rm -rf ".tools/$name"/src-*
        mkdir -p "$src"
        tar -xf "$tarball" -C "$src" --strip-components 1
        for p in $patches; do patch -d "$src" -p1 --quiet <"$p"; done
        if [ "$(field build_system)" = ndk ]; then
            local android_mk=$src/$(field android_mk)
            "$core_ndk/ndk-build" -C "$src/$(field build_subdir)" \
                NDK_PROJECT_PATH="$src" APP_BUILD_SCRIPT="$android_mk" \
                NDK_APPLICATION_MK="$(dirname "$android_mk")/Application.mk" \
                NDK_OUT="$src/build/obj" NDK_LIBS_OUT="$src/build/libs" \
                APP_ABI=arm64-v8a APP_PLATFORM=android-29 APP_STL=c++_static \
                "GIT_VERSION=\"$(field git_version)\"" -j4
        else
            if [ "$name" = ppsspp ]; then
                (cd "$src/ffmpeg" && NDK_PREBUILT="$core_ndk/toolchains/llvm/prebuilt/$host" \
                    NDK_USR_LIB="$core_ndk/toolchains/llvm/prebuilt/$host/sysroot/usr/lib/aarch64-linux-android/21" \
                    bash android_arm64-v8a.sh)
            fi
            "$cmake_bin/cmake" -S "$src" -B "$src/build" -G Ninja -DCMAKE_MAKE_PROGRAM="$cmake_bin/ninja" \
                -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static \
                -DCMAKE_TOOLCHAIN_FILE="$core_ndk/build/cmake/android.toolchain.cmake" "$@"
            "$cmake_bin/cmake" --build "$src/build" --target "${output%_android}"
        fi
        built=$(find "$src/build" -name "$output.so" | head -1)
    fi
    "$ndk/llvm-strip" -s -o "$jni/lib$package.so" "$built"
    if [ "$name" = ppsspp ]; then
        local assets=android/app/build/generated/core-assets/ppsspp
        rm -rf "$assets"
        mkdir -p "$assets"
        cp -R "$src/assets/." "$assets/"
    fi
}

# The embedded cores are built for arm64 only. A core build that asks git for its version must not
# find this repository.
if [ "$abi" = arm64-v8a ]; then
    export GIT_CEILING_DIRECTORIES=$PWD/.tools
    core azahar azahar_libretro azahar_libretro -DENABLE_LIBRETRO=ON -DANDROID_PLATFORM=android-21
    core melonds-ds melondsds_libretro_android melondsds_libretro -DANDROID_PLATFORM=android-29 \
        -DCMAKE_BUILD_TYPE=Release -DENABLE_OPENGL=OFF \
        -DMELONDS_REPOSITORY_TAG="$(sed -n 's/.*"melonds_commit": "\(.*\)".*/\1/p' \
            third_party/melonds-ds/manifest.json)"
    core mgba mgba_libretro mgba_libretro -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_LIBRETRO=ON -DSKIP_LIBRARY=ON -DBUILD_SHARED=OFF -DBUILD_STATIC=OFF -DBUILD_QT=OFF \
        -DBUILD_SDL=OFF -DUSE_FFMPEG=OFF -DUSE_ZLIB=OFF -DUSE_MINIZIP=OFF -DUSE_LIBZIP=OFF \
        -DUSE_PNG=OFF -DUSE_SQLITE3=OFF -DUSE_ELF=OFF -DUSE_LZMA=OFF -DUSE_EDITLINE=OFF \
        -DUSE_DISCORD_RPC=OFF -DENABLE_SCRIPTING=OFF -DUSE_LUA=OFF -DUSE_GDB_STUB=OFF \
        -DUSE_DEBUGGERS=OFF
    core fceumm libretro fceumm_libretro
    core bsnes libretro bsnes_libretro
    core pcsx-rearmed libretro pcsx_rearmed_libretro
    core ppsspp ppsspp_libretro_android ppsspp_libretro -DANDROID_PLATFORM=android-29 \
        -DCMAKE_BUILD_TYPE=Release -DLIBRETRO=ON -DUNITTEST=OFF -DATLAS_TOOL=OFF \
        -DUSE_DISCORD=OFF -DUSE_MINIUPNPC=OFF
    source_args=("${core_sources[@]}")
    if [ "$target" = release ]; then
        source_args=(--sources artifacts/release/miel-source.tar.gz "${source_args[@]}")
    fi
    python3 scripts/package-licenses.py "${source_args[@]}"
fi

android/gradlew -p android ":app:$task" "${gradle_args[@]}"
