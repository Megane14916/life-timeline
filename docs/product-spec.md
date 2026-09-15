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

Androidアプリは収集、端末内pendingの保持、PCへの同期状態の表示を担当します。アプリ利用履歴と写真収集は個別に有効化・診断できます。

写真設定は明示的なopt-in操作からpermissionを要求します。Android 14以降はfull / partial / deniedを区別し、partial時は選択写真の再選択入口を表示します。写真へのアクセスpermissionだけでは収集を開始しません。

# 6. 写真仕様

## 収集対象と許可

写真収集は利用者が有効化した後に開始します。MediaStoreに登録され、公開済みの`DCIM/`配下の画像が対象です。`DCIM/`はCameraアプリを厳密に識別するものではなく、DCIM外のCamera画像は対象外になり、DCIM内の非Camera画像を含む場合があります。

full accessでは有効化時にbaselineを取り、以後に追加された写真を収集します。過去のlibrary全体は自動importしません。Android 14以降のpartial accessでは利用者が選択した写真だけを取り込み、未選択写真の自動収集は保証しません。permission取消・OSによる権限reset後は再度許可が必要です。

## サムネイルと保存

写真原本はPCへ送信せず、Androidで生成したpreviewだけを同期します。

- 最大辺512px、WebP lossy、quality 65、拡大なし
- 端末内thumbnailは1件あたり最大1 MiB
- 1回最大20件、HTTP request全体は最大20 MiB
- PCはmetadataを`lifelog.db`、thumbnailを`thumbnails/`へ保存する

撮影時刻、filename、dimensions、MIME type、取得できるEXIF緯度・経度を保存します。EXIF位置は必要なpermissionがある場合だけ読み取り、取得できない場合はnullにします。Android原本の削除はPCのrecordやthumbnailを削除しません。life-timelineは写真backupサービスではありません。

## 自動処理と非保証

- 収集は15分周期 / 5分flexのWorkManager periodic workで実行します。低battery / storage時は遅延します。
- 同期はunmetered network、BatteryNotLow、StorageNotLowを満たす場合に実行します。metered networkではpendingを保持します。
- 1回の同期は最大8分または10 batchで区切り、batchは20件です。通信・WorkManager処理はOSやPCの状態に左右され、指定時刻の実行を保証しません。
- Androidアプリ内「写真を今すぐ確認」はone-time scanを要求します。



# 7. 位置情報仕様

目的は高精度なリアルタイム追跡ではなく、

**その日にどこへ行ったかを振り返れること**

です。

そのため位置取得頻度はバッテリー消費とのバランスを優先します。

実装値:

- Fused Location Providerの`PRIORITY_BALANCED_POWER_ACCURACY`
- 要求間隔・最小間隔は5分、batchの最大遅延は15分
- Androidのforeground / background location permissionと位置情報サービスを段階的に確認する
- foreground service、reverse geocoding、road snapping、cloud分析は使用しない

5分は要求間隔であり、実行・配信期限ではありません。Doze、OEM battery optimization、端末の位置判定、Google Play services、権限、電波の影響で遅延・欠測が起こります。生位置情報はPC側の`location_points`へ正本として保存し、`stay_point_v1`のPlaceVisitを派生させます。過去の移動は、位置収集を有効にした後に受信・同期済みのraw pointだけが対象で、端末やGoogle Play servicesから有効化前の履歴を取得する機能はありません。

Mapは正確なaccuracyがある1,000m以下のpointを表示候補にし、30分超の欠測区間は線を分割します。200m / 15分 / 3 pointのPlaceVisit heuristicは滞在の推定であり、地名・実際の入退場時刻・すべてのGPS fixの正しさを保証しません。raw pointは自動削除・補正せず、PCの`lifelog.db`を重要な個人データとしてbackupします。

---

# 8. 同期仕様

Androidで収集したデータはRoomへ保存し、PCのAPIからaccepted ACKを受けたrecordだけをsyncedにします。未ACKのAppSession、LocationPoint、写真はデータ種別ごとのpendingに残して再送します。LocationPointの同期は最大200件のbatch、最大20 batchまたは8分のrun budgetで処理し、PC側の`location_points`を正本として保持します。

写真のWorkManager同期は`UNMETERED`、`BatteryNotLow`、`StorageNotLow`を要求します。PC停止・Tailscale未接続・metered network中は送信を待ち、条件回復後に再開します。15分周期 / 5分flexはworkの実行期限ではありません。日々の運転条件と復旧手順は[READMEの写真運用](../README.md#写真の収集と同期)を参照してください。

位置同期はPC停止、Tailscale未接続、network障害でpendingを保持します。位置情報は正確な生活圏を含むため、PCの`lifelog.db`をthumbnailと同じdata rootのbackup対象にします。permission、battery、tile privacy、障害切り分けは[Phase 5位置情報の運用手順](development/phase5-location-operations.md)を参照してください。

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

現在はExport / Backup UIや自動backup機能を提供していません。手動backupではPCのdata rootにある`lifelog.db`と`thumbnails/`を一体でコピーし、手順は[README](../README.md#手動バックアップと復旧)に従います。Export / Backup UIはPhase 7の候補です。

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
