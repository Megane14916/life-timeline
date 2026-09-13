# 写真スキャン取りこぼしの実機確認

Issue [#90](https://github.com/Megane14916/life-timeline/issues/90) で修正した、MediaStoreの一時公開から公開済みへの変化を差分スキャンが拾うこと、および旧cursorで未登録だった写真を回復できることを確認する。

## 更新後の確認

1. PCのBackendとFrontendを起動し、Frontendを今日の日付にする。
2. Androidアプリを既存データを保持したまま、新しい修正版APKへ更新する。アプリデータを消去するとRoom migrationの確認にならない。
3. アプリを開き、写真収集が有効で「写真へのアクセス」が「有効（すべての写真）」であることを確認する。
4. 「写真を今すぐ確認」を押し、scanが成功した後、thumbnail生成待ち・写真pending・端末内thumbnail容量の変化を確認する。
5. Backendが同期可能な状態で、Frontendの今日のPhotos / Timelineへ写真が表示されることを確認する。以前欠けていた有効化後の写真も対象に含まれる。
6. もう一度scanし、PCのitems件数が増えないことを確認する。同じMediaStore IDの再走査は重複登録されない。

## 新規撮影の確認

1. 写真収集を有効にし、Full photo accessを確認してからCameraでDCIM/Cameraへ検証用写真を撮る。
2. アプリを開いたまま、またはバックグラウンドのまま次の自動scanを待つ。再現確認では「写真を今すぐ確認」も実行する。
3. scan後にthumbnail生成待ち、写真pending、端末内thumbnail容量が増え、同期後にPCの今日の一覧へ1枚追加されることを確認する。
4. 続けてscanしてもPC上で同じ写真が増殖しないことを確認する。

## 記録する値

試行前後で次の件数と状態だけを記録する。写真、filename、MediaStore ID、path、hash、座標はIssueやCI artifactへ添付しない。

- Android: scan結果と時刻、thumbnail生成待ち、写真pending、端末内thumbnail容量、直近エラーの有無
- PC: 今日のPhotos API / 画面のitems件数
- 試行: 修正版APKへの更新方法、Full / Partial access状態、新規撮影後にscanを実行したか

新規撮影分も旧cursorからの回復分も表示されない場合は、上記の集計値を添えて報告し、Android側のpending数が0かどうかでMediaStore検出とPC同期の境界を切り分ける。
