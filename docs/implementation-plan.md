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

---

## Phase 3: Automatic Sync

目的:

手動同期を不要にする。

内容:

- WorkManager
- retry
- batch sync
- pending / synced管理
- PC停止時の復旧
- ネットワーク切替テスト

完成条件:

Androidを普段通り利用するだけでPCへデータが蓄積される。

---

## Phase 4: Photos

目的:

その日に撮った写真をライフログとして表示する。

内容:

- MediaStore
- 写真メタデータ
- サムネイル生成
- WebP圧縮
- PCへのアップロード
- Timeline表示
- 写真一覧

完成条件:

原本を送らず、撮影写真をPC上で識別できる。

---

## Phase 5: Location

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

その日の移動経路と主な滞在場所をPCで確認できる。

---

## Phase 6: ActivityWatch

目的:

PC上の行動をlife-timelineへ統合する。

内容:

- ActivityWatch REST Adapter
- desktop_sessions
- Web履歴
- PC利用時間
- Timeline統合

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
