#!/bin/sh
# カバネリ カウントツール APK ビルド（Android SDK 不要: aapt2 + javac + dex2jar + uber-apk-signer）
# 使い方: KS=鍵ファイル KS_PASS=パスワード sh android/build.sh 版 内部番号   例) sh android/build.sh 1.4 5
# 道具: tools/android.jar (Sable/android-platforms android-34), tools/uber.jar (uber-apk-signer 1.3.0),
#       tools/dex-tools-v2.4 (dex2jar 2.4), pip の aapt2。鍵ファイルとパスワードはリポジトリに入れない。
set -e
cd "$(dirname "$0")"
A=$(python3 -c "import aapt2,os;print(os.path.join(os.path.dirname(aapt2.__file__),'bin/Linux/aapt2'))")
T=${TOOLS:-tools}; V=${1:-1.0}; C=${2:-1}
: "${KS:?鍵ファイルのパスを KS に}"; : "${KS_PASS:?パスワードを KS_PASS に}"
cp ../index.html proj/assets/index.html
rm -rf out && mkdir -p out/classes out/gen
$A compile --dir proj/res -o out/res.zip
$A link -I $T/android.jar --manifest proj/AndroidManifest.xml -A proj/assets --version-name "$V" --version-code "$C" --replace-version --java out/gen -o out/base.apk out/res.zip
javac -nowarn --release 8 -cp $T/android.jar -d out/classes proj/src/io/github/sat0121/kabaneri/*.java out/gen/io/github/sat0121/kabaneri/R.java
(cd out/classes && jar cf ../classes.jar .)
sh $T/dex-tools-v2.4/d2j-jar2dex.sh -f -o out/classes.dex out/classes.jar >/dev/null
(cd out && zip -q -j base.apk classes.dex)
java -jar $T/uber.jar -a out/base.apk -o out/signed --ks "$KS" --ksAlias kabaneri --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS" | grep -i "verified\|error"
cp out/signed/base-aligned-signed.apk "out/kabaneri-counter-$V.apk"
rm -f proj/assets/index.html
$A dump badging "out/kabaneri-counter-$V.apk" | head -1
