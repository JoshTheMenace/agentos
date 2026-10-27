# Android toolchain env. Usage: source tooling/android-env.sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0   # d8, aapt2, apksigner, zipalign
export ANDROID_JAR=$ANDROID_HOME/platforms/android-33/android.jar
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_BUILD_TOOLS:$PATH
