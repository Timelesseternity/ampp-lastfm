#!/usr/bin/env bash
# 构建 AM++ Last.fm 插件 ZIP。
# 依赖：JDK 17、android.jar、d8.jar。不需要 Gradle / Android Studio / AM++ 源码。
#
# 用法：
#   bash build.sh
# 可用环境变量覆盖：
#   JAVA_HOME  ANDROID_JAR  D8_JAR  SDK_JAR
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-lib/android.jar}
D8_JAR=${D8_JAR:-lib/d8.jar}
SDK_JAR=${SDK_JAR:-lib/ampp-plugin-api-v1.jar}

if [ -n "${JAVA_HOME:-}" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC=javac
  JAR=jar
  JAVA=java
fi

for f in "$ANDROID_JAR" "$D8_JAR"; do
  [ -f "$f" ] || { echo "缺少 $f"; exit 1; }
done

rm -rf build
mkdir -p build/sdk-classes build/classes build/dex build/dist
mkdir -p "$(dirname "$SDK_JAR")"

# 1) 生成插件 SDK 编译桩（真实 SDK 由宿主在运行时提供）
if [ ! -f "$SDK_JAR" ]; then
  echo "==> 生成 $SDK_JAR"
  find sdk-stub -name '*.java' > build/sdk-sources.txt
  "$JAVAC" -J-Duser.language=en -J-Duser.country=US -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
      -d build/sdk-classes @build/sdk-sources.txt
  "$JAR" cf "$SDK_JAR" -C build/sdk-classes .
fi

# 2) 编译插件源码（SDK 仅 compileOnly，不进入产物）
echo "==> 编译插件"
find src -name '*.java' > build/sources.txt
"$JAVAC" -J-Duser.language=en -J-Duser.country=US -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -classpath "$SDK_JAR" -d build/classes @build/sources.txt

"$JAR" cf build/plugin.jar -C build/classes .

# 3) DEX
echo "==> D8"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release --min-api 26 \
    --lib "$ANDROID_JAR" \
    --classpath "$SDK_JAR" \
    --output build/dex \
    build/plugin.jar

# 4) code.jar（必须包含 classes.dex）
( cd build/dex && "$JAR" cfM ../dist/code.jar classes*.dex )

# 5) 打包 ZIP：根目录直接放 plugin.json / code.jar / assets
cp plugin.json build/dist/
if [ -d assets ] && [ -n "$(ls -A assets 2>/dev/null || true)" ]; then
  cp -r assets build/dist/assets
fi
( cd build/dist && rm -f ../../ampp-lastfm.zip && "$JAR" cfM ../../ampp-lastfm.zip . )

echo
echo "完成：$(pwd)/ampp-lastfm.zip"
echo "安装：Apple Music → 设置 → AM++ 模块设置 → 插件 → 导入 ZIP，启用后完全重启 Apple Music"
