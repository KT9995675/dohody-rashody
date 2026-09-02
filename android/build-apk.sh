#!/bin/zsh
set -euo pipefail
setopt NULL_GLOB

ROOT="$(cd "$(dirname "$0")" && pwd)"

# AGP не работает на JDK 25 (JBR из свежей Android Studio). Нужен 17 или 21.
pick_java_home() {
  local c ver major
  local candidates=(
    "$ROOT/.jdk"/jdk-21*/Contents/Home
    "$ROOT/.jdk"/*/Contents/Home
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
    "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
  )
  for c in "${candidates[@]}"; do
    [[ -x "$c/bin/java" ]] || continue
    ver="$("$c/bin/java" -version 2>&1 | head -1)"
    if [[ "$ver" =~ \"([0-9]+) ]]; then
      major="${match[1]}"
      if (( major >= 17 && major <= 21 )); then
        print -r -- "$c"
        return 0
      fi
    fi
  done
  return 1
}

if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  _ver="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  if [[ "$_ver" =~ \"([0-9]+) ]]; then
    _major="${match[1]}"
    if (( _major > 21 || _major < 17 )); then
      echo "JAVA_HOME=$JAVA_HOME (JDK $_major) не подходит для AGP — ищу JDK 17/21…"
      unset JAVA_HOME
    fi
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(pick_java_home)" || {
    echo "Нужен JDK 17 или 21 (не 25 из Android Studio)."
    echo "Скачайте Temurin 21:"
    echo "  https://adoptium.net/temurin/releases/?version=21&os=mac&arch=aarch64&package=jdk"
    echo "и распакуйте в: $ROOT/.jdk/"
    echo "Или: brew install openjdk@21"
    exit 1
  }
fi
export JAVA_HOME
if [[ -d "$ROOT/.android-sdk/platforms/android-35" ]]; then
  export ANDROID_HOME="$ROOT/.android-sdk"
elif [[ -z "${ANDROID_HOME:-}" ]]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

LOCAL_GRADLE="$ROOT/.gradle-dist/gradle-8.11.1/bin/gradle"
if [[ ! -x "$LOCAL_GRADLE" ]]; then
  if [[ -f "$ROOT/.gradle-dist/gradle-8.11.1-bin.zip" ]]; then
    echo "Распаковываю Gradle…"
    (cd "$ROOT/.gradle-dist" && unzip -qo gradle-8.11.1-bin.zip)
  fi
fi

if [[ -x "$LOCAL_GRADLE" ]]; then
  GRADLE="$LOCAL_GRADLE"
elif [[ -x "$HOME/.gradle/wrapper/dists/gradle-9.3.0-bin/79n14ral3mx1ozqr3csh2u872/gradle-9.3.0/bin/gradle" ]]; then
  GRADLE="$HOME/.gradle/wrapper/dists/gradle-9.3.0-bin/79n14ral3mx1ozqr3csh2u872/gradle-9.3.0/bin/gradle"
else
  GRADLE="$ROOT/gradlew"
  chmod +x "$GRADLE" || true
fi

cd "$ROOT"
echo "Java: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "JAVA_HOME=$JAVA_HOME"
echo "Gradle: $GRADLE"
"$GRADLE" :app:assembleDebug --no-daemon -Dorg.gradle.java.home="$JAVA_HOME"

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  echo ""
  echo "Готово: $APK"
  ls -lh "$APK"
  open -R "$APK" 2>/dev/null || true
else
  echo "APK не найден"
  exit 1
fi
