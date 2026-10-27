#!/bin/sh
# Mac: cross-build llama-server for the phone's CPU (Cortex-A76/A55: ARMv8.2 + dotprod + fp16) and install it in Termux,
# plus the model. ~1 min on the Mac vs >1 h on the phone. Termux's own llama-cpp is built for baseline ARMv8 (~3x slower prefill).
# Needs: brew install cmake ninja; sdkmanager "ndk;28.2.13676358". Take /tmp/agentos-device.lock around it.
set -e
V=0.7.0; NDK=${NDK:-/opt/homebrew/share/android-commandlinetools/ndk/28.2.13676358}; S=${SERIAL:-ZY22J3TK8S}
W=$(cd "${TMPDIR:-/tmp}" && pwd -P)/llama-android; mkdir -p "$W" && cd "$W"   # real path: the web UI step breaks on /tmp symlinks
[ -d llama.cpp-$V ] || curl -sSL https://github.com/ggml-org/llama.cpp/archive/refs/tags/v$V.tar.gz | tar xz
cmake -S "$W/llama.cpp-$V" -B "$W/out" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DBUILD_SHARED_LIBS=OFF -DGGML_BACKEND_DL=OFF \
  -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF >/dev/null
cmake --build "$W/out" --target llama-server llama-bench >/dev/null
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip "$W/out/bin/llama-server" "$W/out/bin/llama-bench"
adb -s "$S" push "$W/out/bin/llama-server" "$W/out/bin/llama-bench" /data/local/tmp/
D=/data/data/com.termux/files/home/agentos/llama/bin
adb -s "$S" shell "su -c 'mkdir -p $D && mv /data/local/tmp/llama-server /data/local/tmp/llama-bench $D/ && chown -R 10401:10401 $D/.. && chmod 755 $D/* && restorecon -R $D'"
"$(dirname "$0")/../tx.sh" 'mkdir -p ~/models && cd ~/models && [ -s Qwen3.5-0.8B-Q4_0.gguf ] || curl -sSLO https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_0.gguf; ls -l ~/models ~/agentos/llama/bin'
