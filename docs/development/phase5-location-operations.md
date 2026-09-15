# Phase 5 位置情報の運用手順

この文書は、Phase 5の位置情報を日常利用するときの前提、確認順序、障害切り分けをまとめたものです。位置情報は正確な生活圏を含むため、実機検証では[Phase 5実機受け入れ手順・記録](phase5-acceptance.md)のprivacy条件を優先します。

## 1. 実装値と非保証

| 項目 | 現行の扱い |
| --- | --- |
| 取得元 | Google Play services Fused Location Provider |
| priority | `PRIORITY_BALANCED_POWER_ACCURACY` |
| 要求間隔 / 最小間隔 | 5分 / 5分 |
| batch最大遅延 | 15分 |
| 配信方式 | package-scoped `PendingIntent`と`LocationUpdatesReceiver` |
| Android同期batch | 最大200 LocationPoint |
| 同期run上限 | 最大20 batchまたは8分 |
| PlaceVisit | `stay_point_v1`: accuracy 200m以下、200m内、15分以上、3 point以上。30分超gapで分割し、10分以内の近接候補をmerge |
| Map route | accuracyがあり1,000m以下のpoint。30分超gapまたはPlaceVisitをまたぐ箇所で分割 |
| foreground service | 使用しない |
| reverse geocoding / road snapping | 使用しない |

5分は要求値であり、実行・配信期限ではありません。Doze、OEMのbattery最適化、permission、位置情報サービス、Google Play services、電波、端末状態によって遅延・欠測します。位置収集を有効にする前の端末履歴を取得する機能もありません。

## 2. 毎日の確認

1. Androidで位置収集のopt-inが有効であることを確認する。
2. foregroundのprecise / approximate状態、background permission、端末の位置情報サービスが意図した状態であることを確認する。approximateは粗いrouteやPlaceVisit 0件になり得る。
3. PCのFastAPIがloopbackで起動し、AndroidはTailscale ServeのHTTPS endpointを使っていることを確認する。LAN直公開、Funnel、cleartext HTTP、TLS検証無効化は使わない。
4. Androidの位置情報pendingが増えた場合は、PC停止中の正常な保持か、同期条件の未達かを確認する。accepted ACK前のrowは削除しない。
5. PC復旧後、pendingが解消し、同じ日付・timezoneのTimeline / Mapへ表示されることを確認する。背景tileは必要なときだけ明示的に有効化する。

## 3. 症状別の切り分け

| 症状 | 確認する順序 | 正常な非保証 / 注意 |
| --- | --- | --- |
| 最新受信が未実行 | opt-in、precise / approximate、background permission、位置情報サービス、登録状態 | 移動しないこと自体は取得不能の理由ではないが、OSがbatchをまだ配信していない場合がある |
| 位置登録が再試行待ち | 位置情報サービス、permission、Google Play services、battery最適化、登録workの直近エラー | 12時間watchdogとunique workによる再登録はbest-effort。常時通知やforeground serviceで補完しない |
| pendingが減らない | PC health、Tailscale Serve、endpoint、metered network、PCのdata directory | ACKされていないpointを削除しない。復旧後の自動retryまたは手動同期を確認する |
| pendingは0だがPCにない | AndroidとPCが同じendpoint / data directoryか、PC APIの受信結果、Mapの日付・timezone | Mapは当日のquery範囲だけ。同期成功とMap表示対象は別に確認する |
| routeが途切れる | pointの受信時刻、30分超gap、PlaceVisitの時間範囲 | 欠測区間は補間しない。5分ごとの連続線を保証しない |
| 不自然なpointがある | まずraw pointを削除・補正せず、同日の受信済みbatchとaccuracyを確認する | 初版はGPS jumpをheuristicで自動判定・除外しない。実座標や画面画像をIssue / PRへ添付しない |
| PlaceVisitがない | 3 point以上、15分以上、accuracy 200m以下、同一device、200m以内を確認 | PlaceVisitは滞在推定であり、地名や実際の入退場を保証しない |
| tileが表示されない | 背景tileの明示opt-in、browser network、OpenStreetMap availability | tile failureでもlocal overlay、route、visit、text summaryは利用できる。tile providerへAPI payloadやmarker dataを送らない |

確認結果を共有するときは、`PASS` / `FAIL` / `BLOCKED`と症状カテゴリだけを残します。緯度・経度、住所、route geometry、point timestamp、端末ID、hostname、tailnet名、endpoint、token、DB、raw log、packet captureは保存・共有しません。

## 4. Backupと復旧

位置情報の正本はPCの`$env:LIFE_TIMELINE_DATA_DIR\lifelog.db`にある`location_points`です。PlaceVisitは再生成可能な派生Factですが、raw pointを失うと将来の判定変更・再集計ができません。写真を使っている場合は同じdata rootの`thumbnails/`と一体でsnapshotを作り、DBだけを除外しないでください。現時点で自動backup機能はありません。

復旧時はFastAPIを停止し、data root全体を同じsnapshotから戻します。AndroidのpendingはPC復旧後に再送されるため、復旧確認のためにアプリデータ消去やRoom rowの手動削除を行いません。

## 5. Tile privacy

Mapの初期表示はlocal overlayとtext summaryだけで、外部tile requestを行いません。「オンライン背景地図を表示」を利用者が明示的に押した場合だけ、browserが設定済みのOpenStreetMap tileへアクセスします。online basemapはbest-effortであり、tile availabilityやoffline利用を保証しません。自動prefetch、offline download、headless E2Eからのtile取得は行いません。

## 6. Phase 6への境界

Phase 6のActivityWatch連携はPC側collectorとして実装し、Androidのlocation permission、Fused Location registration、Room、Location sync、OSM tile取得を共有しません。再利用するのは、`app_sessions` / `place_visits` / `media_items`を統合するTimeline query、timezone日範囲、raw Factとderived Factの分離、data type別worker / lease / retry、batchと冪等性の形式です。ActivityWatch bucket、event cursor、window title / URLのprivacy、PC collector scheduleはPhase 6で別途決定します。

## 参照

- [Phase 5詳細計画](../detailed_plan/phase5-location.md)
- [Phase 5実機受け入れ手順・記録](phase5-acceptance.md)
- [開発toolchain](toolchains.md)
- [READMEの位置情報の収集と同期](../../README.md#位置情報の収集と同期)
