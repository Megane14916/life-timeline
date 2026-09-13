# Phase 4 受け入れ記録

## 判定

| 対象 | 結果 | 根拠 |
| --- | --- | --- |
| 自動テストとrequired CI | PASS | P4-08の合成データテスト、PR #91の写真スキャン修正とCI。PR #87の最新CI状態はPR上で確認する |
| Android実機の通常系 | PASS | PR #91を含むビルドを再インストール後、収集有効化後に撮影した写真がPCへ同期されることを利用者が確認。同期件数・所要時間は未記録 |
| 拡張実機シナリオ | 任意・未実施 | 部分権限、通信断、process kill、再起動、20枚超burst、24時間運転など。Phase 2・3と同じくPhase 4完了のblockerにしない |

通常系は受け入れ済みとして扱う。拡張実機シナリオは実施していないことを明示し、PASSとは記録しない。実機やOEMに固有の不具合が報告された場合は、そのシナリオだけを再現し、別Issueで追跡する。

## 通常系の確認

1. 写真収集を有効にし、端末の写真アクセスを許可する。
2. 有効化後に検証用写真を撮影する。
3. 自動同期またはアプリの「写真を今すぐ確認」で処理し、PCの今日のTimeline / Photosに写真が表示されることを確認する。
4. 再scanしても同じ写真が重複表示されず、同期後にpendingが解消することを確認する。

上記の通常系は、利用者から新規撮影分を含む写真がすべて同期されたと報告され、複数回の確認でも成功した。正確な件数、byte数、所要時間は測定・保存していないため記載しない。

## 任意の拡張シナリオ

以下は今回未実施であり、通常系と自動CIを通すために追加で実行する必要はない。

- Android 14以降のpartial / denied / 写真再選択
- EXIF位置・複数向きの実機差、通信量・battery・storageの長時間測定
- metered network、PC停止、Tailscale切断からの復旧
- process kill、端末再起動、stale lease、未ACK再送
- 20枚超burst、ACK後の原本削除、24時間以上のschedule

必要になった場合は専用データで実施し、件数・byte数・経過時間などの集計値だけを記録する。写真、thumbnail、filename、source ID、hash、座標、hostname、端末ID、raw log、packet captureはIssueやrepositoryへ保存しない。

## 自動テストと実機確認の境界

- MediaStore / Room / WorkManager、API、PC表示、冪等性、thumbnail処理など、CIで再現可能な契約はunit / instrumentation / E2Eとrequired CIを証拠にする。
- 実機では通常撮影後に写真がPCへ届く経路を確認する。個別OS/OEMの権限dialog、電池最適化、長時間運転などは追加保証が必要になった場合の任意確認とする。
- 原本をPCへ保存・送信しない設計と検証は、自動テストおよび実装境界で確認する。実際のwire byte数は測定していない。
- 写真や個人を識別できる情報をPR、Issue、ログ、CI artifactへ添付しない。

## 参照

- [Phase 4詳細計画 §12 / P4-09](../detailed_plan/phase4-photos.md)
- [Phase 3受け入れ記録](phase3-acceptance.md)
- [P4-08の合成データ・CI](https://github.com/Megane14916/life-timeline/pull/86)
- [MediaStore公開後の差分scan修正](https://github.com/Megane14916/life-timeline/pull/91)
