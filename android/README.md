# Android版（APK）のソース

カウンター本体は親フォルダの `index.html` をそのまま `assets/` に入れて同梱する。

- `proj/src/.../MainActivity.java`：カウンターと台選び（asabu-kabaneri）の2画面
- `proj/src/.../Fetcher.java`：台選びの取得（通信優先・保存分で予備）
- `proj/src/.../CzNotify.java`・`CzReceiver.java`：通知からのCZポイント入力（1.4〜）
- `proj/assets/picker-shell.js`：台選び画面に戻るボタンなどを足す
- `icon-mk.py`：アイコン（筆文字「六」、Yuji Boku）の生成
- `build.sh`：ビルドと署名。鍵ファイル `kabaneri-release.jks` とパスワードは手元で保管し、ここには入れない

版：1.0(1) → 1.1(2) 台選び → 1.2(3) アイコン → 1.3(4) 当日履歴 → 1.4(5) 通知からCZポイント入力
