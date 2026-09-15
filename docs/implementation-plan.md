# life-timeline 実装計画

このドキュメントでは全体の実装順序のみを定義します。

各フェーズの詳細なタスク・API・DB変更・テスト計画は、実装開始前に個別ドキュメントとして作成します。

## Phase 0: Project Setup

目的:

開発基盤を作る。

内容:

- GitHub repository作成
- monorepo構成作成
- React + Vite初期化
- FastAPI初期化
- Androidプロジェクト初期化
- Formatter / Linter導入
- GitHub Actionsの最小CI
- docs整備

完成条件:

各プロジェクトがローカルとCIでbuildできる。

---

## Phase 1: PC Core

目的:

PC単体でライフログデータを保存・表示できるようにする。

内容:

- SQLite
- migration
- FastAPI
- 正規化データモデル
- Timeline API
- 基本的なStatistics API
- React Timeline
- 最小Dashboard
- ダミーデータ投入
- 日付切替

完成条件:

同じダミーデータをTimelineと基本統計の両方から閲覧できる。

---

## Phase 2: Android App Usage MVP

状態: 完了。実機での受け入れ結果は[Phase 2受け入れ記録](development/phase2-acceptance.md)を参照してください。

目的:

Android → PC の同期経路を一本完成させる。

内容:

- Room
- UsageStatsManager
- アプリ使用Session生成
- 手動Sync
- FastAPI Sync API
- Tailscale通信
- Timeline表示

完成条件:

実機Androidのアプリ利用履歴がPC Timelineに表示される。

Phase 2の受け入れでは手動同期経路と再送の冪等性を確認しました。定期収集・自動同期はPhase 3で実装済みであり、画面の手動操作は診断・即時実行手段として残ります。

---

## Phase 3: Automatic Sync

状態: 実装完了。WorkManager、Room v2、期限付きlease、自動retry、手動 / 自動の共通実行基盤、CI gateをmainへ反映済みです。正常系の確認と実機確認の範囲は[Phase 3受け入れ記録](development/phase3-acceptance.md)を参照してください。

開始条件: Phase 2の実機同期、再送時の冪等性、Timeline / Dashboard表示、required checksが受け入れ記録に残っていること。

目的:

通常利用中の手動同期を不要にし、必要な場合だけ診断・即時実行として残す。

内容:

- WorkManager
- retry
- batch sync
- pending / synced管理
- PC停止時の復旧
- ネットワーク切替テスト

完成条件:

Androidを普段通り利用するだけでPCへデータが蓄積される。

Phase 3で確定した主な運用値は、collectionの15分周期 / 5分flex、syncの`CONNECTED`かつ`BatteryNotLow`、指数backoff初期15分、100件batch、1回8分または20 batch上限です。Doze、OEM最適化、force-stop中の無人復旧の厳密性は保証対象外で、必要に応じて実機受け入れ記録の任意シナリオで確認します。

---

## Phase 4: Photos

状態: P4-01〜P4-09の実装と写真収集の通常系受け入れが完了しています。P4-10では主要文書とPhase 5引き継ぎをこのPRで反映します。受け入れ結果と任意シナリオの範囲は[Phase 4受け入れ記録](development/phase4-acceptance.md)、固定値と非保証は[Phase 4詳細計画](detailed_plan/phase4-photos.md)を参照してください。

開始条件: Phase 3の正常系、CI gate、Room / WorkManagerの統合テスト、受け入れ手順がmainへ反映されていること。Phase 3のscheduler、WorkerFactory、期限付きlease、retry分類、safe diagnosticsを再利用し、写真用work nameとleaseは独立させます。

目的:

写真のmetadataと軽量thumbnailをローカルPCへ集め、Timelineと日別Photosで振り返れるようにする。

内容:

- MediaStore / Android 14以降のfull・partial access
- 有効化後のDCIM写真を対象にするbaselineと差分scan
- metadataと最大辺512px、WebP lossy quality 65のthumbnail生成
- unmetered network限定の写真同期、冪等なAPI保存
- Timeline union、Photos一覧、thumbnail配信
- 実装値・運用・backup対象・Phase 5引き継ぎの文書

完成条件:

原本を送らず、許可範囲内の写真がPC上で識別でき、required CIと通常系の実機確認が受け入れ記録に残る。拡張実機シナリオは任意とする。

## Phase 5: Location

状態: 実装完了。P5-01〜P5-09の自動検証とP5-10の実機受け入れ手順を反映しています。実機のOS配信時刻、OEM差、Doze、battery、長期運転はbest-effort・任意確認として扱い、未実施をPASSとはしません。固定値・非保証・privacy境界は[Phase 5詳細計画](detailed_plan/phase5-location.md)、運用は[Phase 5位置情報の運用手順](development/phase5-location-operations.md)を参照してください。

開始条件: Phase 4の文書引き継ぎがmainへ反映されていること。Photosの正規化media_items、timezone日範囲query、EXIF latitude / longitude、写真固有worker / lease / retry、privacy用合成fixtureを再利用します。LocationPointの連続記録・PlaceVisitは独立した保存モデルとし、写真workerやACCESS_MEDIA_LOCATIONへ混在させません。実装前にLocation固有のpermission、work name、周期、battery budget、同期batch、Map表示を決定します。

目的:

1日の移動を記録する。

内容:

- Fused Location Provider
- Background location
- Room保存
- Location同期
- Map UI
- PlaceVisit生成

完成条件:

合成fixtureによるrequired CIが成功し、位置収集を有効化した後に同期されたraw pointから、その日の移動経路と主な滞在場所をPCで確認できる。5分ごとの到着、完全な軌跡、地名の自動判定、常時高精度は完成条件にしない。

## Phase 6: ActivityWatch

開始条件: Phase 5の保存・同期・query境界とprivacy-safeな運用手順がmainへ反映されていること。Phase 6のPC collectorはAndroidのlocation permission、PendingIntent、Room、OSM tile取得を共有しない。

目的:

PC上の行動をlife-timelineへ統合する。

内容:

- ActivityWatch REST Adapter
- desktop_sessions
- Web履歴
- PC利用時間
- Timeline統合

再利用する境界:

- `app_sessions` / `place_visits` / `media_items`を混在させるTimeline discriminated unionとtimezone日範囲query
- raw Factと再生成可能なderived Factを分けるrepository / CLI pattern
- data type別unique work、lease、retry、safe diagnostics
- batch、冪等ID、transaction、性能計測の形式

完成条件:

PCとAndroidの行動が同じTimeline上で表示される。

---

## Phase 7: Product Polish

目的:

日常利用できる品質へ近づける。

内容:

- Filter / Search
- Statistics画面
- Calendar
- Device management
- Backup
- Export
- 手動イベント
- Error UI
- Settings
- performance改善

---

## Phase 8: Desktop Packaging

必要性を確認した上で実施します。

候補:

- Tauri
- Windows自動起動
- System Tray
- FastAPI background service

ブラウザ版で十分な場合、このPhaseは実施しません。

---

## Phase 9: Optional Integrations

コア機能完成後に検討します。

候補:

- Google Photos Picker
- Google Calendar
- GitHub
- Health Connect
- 歩数
- 音楽履歴
- AIによる検索・要約

AI機能は必須要件ではありません。
