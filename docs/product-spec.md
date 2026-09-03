# life-timeline プロダクト仕様

## 1. プロダクト概要

life-timelineは、自分のデジタル活動と現実世界での行動を自動記録し、後から時系列で振り返るための個人用ライフログアプリです。

目的は生産性向上そのものではなく、**日々の出来事を記録し、タイムライン・統計・地図・写真など複数の視点から振り返れること**です。

---

# 2. ユーザー

初期ターゲット:

- 開発者本人
- Android + Windowsを利用
- 自分のデータをクラウドサービスへ必要以上に送信したくない
- 日々の活動をできるだけ自動で残したい

初期段階ではマルチユーザー機能を持ちません。

---

# 3. ユーザー価値

ユーザーは以下を後から確認できます。

- 今日どこへ行ったか
- 何時ごろどこにいたか
- その場所でどんな写真を撮ったか
- スマホで何のアプリを使っていたか
- PCで何をしていたか
- 1日の大まかな流れ

Timelineは中心機能ですが、表示形式は1つに限定しません。

```text
Timeline   → 1日の流れ
Statistics → 利用時間・移動・写真などの傾向
Map        → 行動範囲・訪問場所
Photos     → 撮影記録
History    → 過去データの検索・絞り込み
```

データ保存形式自体はTimelineに依存しないものとします。

---

# 4. コア機能

## Timeline

日付単位で、その日の出来事を時系列表示します。

イベント例:

```text
08:42
PC / Chrome

09:13
PC / VS Code

10:22
自宅を出発

10:54
東京駅周辺

10:57
写真 × 3

11:04
Android / Google Maps / 8min

11:25
丸の内周辺
```

表示対象:

- Android AppSession
- Desktop Session
- PlaceVisit
- Photo
- Manual Event

---


## Statistics

Timelineと同じ原本データを、日・週・月などの単位で集計して表示します。

主な統計候補:

- 総デバイス利用時間
- PC / Android別利用時間
- アプリ別・カテゴリ別利用時間
- セッション回数・平均セッション時間
- 曜日別・時間帯別の傾向
- 移動距離・外出時間
- 場所別滞在時間・訪問回数
- 写真・動画の撮影数
- 日次・週次・月次推移

将来的には、外出時間と写真枚数、場所とアプリ利用時間など、異なる種類のデータを組み合わせた分析も行える構造にします。


## Calendar

日付を選択し、その日のTimelineへ移動できます。

将来的には以下も表示します。

- 記録件数
- 写真枚数
- 移動量
- PC使用時間

---

## Map

位置履歴を地図上に表示します。

表示内容:

- 移動経路
- PlaceVisit
- 写真撮影地点
- 手動イベント

---

## Photos

撮影した写真を日付別に表示します。

life-timelineでは原本を保存せず、プレビュー用サムネイルのみを保持します。

表示情報:

- サムネイル
- 撮影時刻
- 撮影地点
- ファイル名
- 撮影端末

---

## App Usage

Androidアプリの利用履歴を表示します。

例:

```text
Chrome       42 min
YouTube      31 min
Google Maps  18 min
Discord      14 min
```

TimelineではSession単位で表示します。

---

## Desktop Activity

ActivityWatchからPC利用履歴を取り込みます。

例:

- VS Code
- Chrome
- Terminal
- Discord
- アクティブウィンドウ
- Webページ

---

# 5. Android Companion

Androidアプリの主目的は収集と同期です。

メイン画面の想定:

```text
life-timeline

PC
Connected

Last Sync
18:32

Pending
App Usage      12
Locations      84
Photos          3

[Sync now]
```

設定:

- 必要な権限
- 位置情報記録ON/OFF
- 同期間隔
- 写真同期ON/OFF
- アプリ履歴ON/OFF
- 同期状況
- エラー確認

---

# 6. 写真仕様

## 基本

写真原本はPCへ送信しません。

Android側でサムネイルを生成します。

想定:

```text
最大辺 512px
WebP
quality 60〜70
```

PCでは、

- メタデータ → SQLite
- サムネイル → filesystem

へ保存します。

## 原本

原本の保管は以下に任せます。

- Android端末
- Google Photos等

life-timelineは写真バックアップサービスではありません。

## Google Photos

MVPでは連携しません。

将来的にGoogle Photos Pickerを利用し、ユーザーが選択した写真のインポートを検討します。

---

# 7. 位置情報仕様

目的は高精度なリアルタイム追跡ではなく、

**その日にどこへ行ったかを振り返れること**

です。

そのため位置取得頻度はバッテリー消費とのバランスを優先します。

初期案:

- 約5分周期
- または一定距離移動時

生位置情報からPC側でPlaceVisitを生成します。

---

# 8. 同期仕様

Androidで収集したデータは、一度Roomへ保存します。

```text
収集
↓
Room
↓
PCへ同期
↓
PC ACK
↓
同期済み
```

PCへ接続できない場合も記録を続行します。

同期条件:

- PCがオンライン
- Tailscale経由でアクセス可能

将来的にはWi-Fi接続時のみ写真同期するなどの設定を追加できます。

---

# 9. 削除仕様

life-timelineは履歴を保存するプロダクトです。

そのため、元データがAndroidから削除されてもlife-timeline上の記録は原則として自動削除しません。

例:

```text
9/3
写真を撮影
↓
life-timelineへ同期

9/10
スマホから原本削除

life-timeline:
サムネイルと撮影記録は残る
```

ユーザーがlife-timeline上から明示的に削除する機能は将来的に提供します。

---

# 10. 手動イベント

自動記録では分からない文脈を補完するため、手動イベントを追加できるようにします。

例:

```text
10:00〜18:00
「ハッカソン」

20:00
「友達と食事」
```

Timeline上で自動ログと一緒に表示します。

MVP後の追加機能とします。

---

# 11. 検索・フィルター

将来的に以下を提供します。

フィルター:

- PC
- Android
- 写真
- Location
- App
- Manual Event

検索例:

```text
VS Code
東京駅
YouTube
2026/09
```

AI検索は必須としません。

---

# 12. データ所有

life-timelineの基本方針:

> ユーザー自身がライフログデータを所有する。

PC上のローカルデータを正とします。

クラウドアカウントを必須にしません。

データのExport / Backupを提供します。

---

# 13. プライバシー

収集対象は非常に機密性が高いため、以下を重視します。

- LANやインターネットへAPIを直接公開しない
- 通信はTailscale
- クラウドを必須にしない
- 写真原本をlife-timelineへコピーしない
- 不要なデータを収集しない
- 各CollectorをON/OFF可能にする

---

# 14. MVP受け入れ条件

MVP完成条件:

1. Android実機でアプリ利用履歴を取得できる
2. データをRoomへ保存できる
3. PCが停止中でも記録を失わない
4. Tailscale経由でPCへ送信できる
5. PCのSQLiteへ保存できる
6. 重複送信しても二重登録されない
7. React Timelineに利用履歴が表示される
8. 日付を変更して過去ログを確認できる

---

# 15. 将来候補

- Google Photos Picker
- Google Calendar連携
- GitHub activity
- Health Connect
- 歩数
- 睡眠
- 音楽再生履歴
- PCスクリーンショット
- Screenpipe連携
- データ統計
- AI検索
- AIによる日次要約
- Tauriによるデスクトップアプリ化
