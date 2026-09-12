# Phase 4 詳細実装計画: Photos

- 作成日: 2026-09-12
- 対象: Androidの写真検出、サムネイル生成・保管・自動同期、PCのTimeline / 写真一覧
- 前提: Phase 3の正常系、自動収集・自動同期基盤、CI gate、Room / WorkManager統合テスト、受け入れ手順がmainへ反映済みであること

## 1. 参照資料とPhase 4の位置付け

| 資料                                                        | 確定済みの前提                                                                                       | Phase 4での扱い                                                                       |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| [implementation-plan.md](../implementation-plan.md)         | Phase 4はMediaStore、写真メタデータ、512px WebPサムネイル、PC upload、Timeline、写真一覧を対象とする | 原本を送らず、その日に撮った写真をPC上で識別できる経路を完成させる                    |
| [product-spec.md](../product-spec.md)                       | 原本はAndroid / Google Photosへ任せ、PCにはメタデータとサムネイルだけを残す                          | Android原本の削除をPC側へ伝播させず、履歴のsnapshotとして保持する                     |
| [data-model.md](../data-model.md)                           | PCの`media_items`は正規化Factで、Photos / Timeline / Map / Exportから再利用する                      | photo専用の同期経路を作るが、PC保存形式は将来のvideoやLocation統合を妨げない          |
| [architecture.md](../architecture.md)                       | Androidは収集と一時保存、PCはSQLiteとfilesystemを正本とする                                          | Androidのapp-private thumbnailを一時queue、PCの`thumbnails/`を長期保存先とする        |
| [technical-design.md](../technical-design.md)               | 種類別batch、ACK済みだけsynced、写真batch目安20件、原本非送信                                        | multipart contract、容量上限、hash検証、atomic file保存を追加する                     |
| [phase3-automatic-sync.md](phase3-automatic-sync.md)        | unique work、WorkerFactory、期限付きRoom lease、retry分類、安全な診断が完成                          | 考え方と共通部品を再利用し、写真用worker / work name / lease / budgetは分離する       |
| [phase3-acceptance.md](../development/phase3-acceptance.md) | 正常系はPASSで、OS・OEM依存の拡張実機シナリオは非保証・任意として分離されている                      | Phase 4も通常系の完成条件と拡張実機保証を分け、記録には件数・容量・経過時間だけを残す |
| [toolchains.md](../development/toolchains.md)               | AndroidはminSdk 26、target / compile API 36、JDK 17                                                  | API levelごとのMediaStore・permission差をadapter内へ閉じ込める                        |

Phase 3は実装完了であり、正常系の自動収集・自動同期、Room version 2、`BackgroundWorkScheduler`、`WorkerFactory`、期限付きlease、retry分類、safe diagnostics、6 required checksをPhase 4の確定済み基盤として扱う。Phase 3で任意とされたDoze、OEM最適化、端末再起動、process kill、24時間以上の実機シナリオはPhase 4開始のblockerへ戻さない。写真の容量・codec・`UNMETERED`制約に関係する項目だけを、Phase 4の実機受け入れで改めて検証する。

### 1.1 Android公式仕様から採用する制約

- Android 13以降は`READ_MEDIA_IMAGES`、Android 12L以前は`READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`）を使う。動画権限は要求しない。
- Android 14以降のSelected Photos Accessを正しく判定するため、`READ_MEDIA_VISUAL_USER_SELECTED`をmanifestへ宣言し、full / partial / deniedを実行時に毎回判定する。permission状態をDataStoreへ正として保存しない。[Selected Photos Access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- runtime permissionはアプリ起動時に突然要求せず、写真収集を有効にするユーザー操作から要求する。partial access時には再選択入口を表示する。
- Android 10以降でunredacted EXIF位置を読む場合だけ`ACCESS_MEDIA_LOCATION`と`MediaStore.setRequireOriginal()`を使う。位置権限が拒否されても写真同期は継続し、緯度・経度を`null`にする。[共有ストレージ上のmediaへのアクセス](https://developer.android.com/training/data-storage/shared/media)
- API 30以降はvolumeごとの`MediaStore.getVersion()`と`GENERATION_ADDED`を使う。version変更時はgenerationがresetされたものとして、収集開始時刻以降を再走査する。API 26〜29は`DATE_ADDED`と`_ID`の複合cursorを使う。[MediaStore API](https://developer.android.com/reference/android/provider/MediaStore)
- API 29以降は`ContentResolver.loadThumbnail()`でbounded decodeする。API 26〜28は`BitmapFactory`のbounds / sample decodeとEXIF orientation補正をadapterへ閉じ込める。[thumbnail生成](https://developer.android.com/social-and-messaging/guides/media-thumbnails)
- WebPはAPI 30以降で`Bitmap.CompressFormat.WEBP_LOSSY`、API 26〜29で互換用`WEBP`を使う。[Bitmap.CompressFormat](https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat)
- `androidx.exifinterface:exifinterface:1.4.2`を採用候補とし、実装Issue開始時にstable releaseを再確認してversion catalogとtoolchainsへ固定する。[ExifInterface release notes](https://developer.android.com/jetpack/androidx/releases/exifinterface)
- 写真uploadには`NetworkType.UNMETERED`、`BatteryNotLow`、`StorageNotLow`を使用する。constraintを失ってworkerが停止した場合はcancellationを伝播し、未ACKをpendingに保つ。[WorkManager work constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

## 2. ゴールと実装範囲

### 2.1 到達する状態

```text
ユーザーが写真収集を有効化
  ├─ media permissionを要求し、full / partial / deniedを表示
  ├─ full access: 現在位置をbaseline化し、以後の新規写真を対象にする
  └─ partial access: ユーザーが明示選択した写真だけを対象にする

unique periodic photo collection
  └─ PhotoCollectionWorker
       ├─ MediaStoreの各volumeを差分走査
       ├─ DCIM配下の完成済み画像だけを発見
       ├─ app-private領域へ最大辺512pxのWebPをatomic保存
       ├─ metadata、hash、pending状態をRoomへ保存
       └─ unique photo syncをenqueue

unique photo sync
  └─ UNMETERED && BatteryNotLow && StorageNotLow
       └─ PhotoSyncWorker
            ├─ 古いpendingから最大20件をmultipart送信
            ├─ PCがthumbnailをfilesystem、metadataをSQLiteへ保存
            ├─ accepted IDだけAndroidでsynced
            ├─ ACK後にAndroidの一時thumbnailを削除
            └─ 一時障害はbackoffし、復旧後に続きから送る

PC
  ├─ GET /api/v1/timeline にphoto itemを統合
  ├─ GET /api/v1/photos で日付別一覧を返す
  └─ GET /api/v1/media/{id}/thumbnail でWebPだけを配信
```

通常のCamera撮影後、life-timelineの画面を毎回開かなくても写真のメタデータと軽量サムネイルがPCへ蓄積される。PC停止中やmetered network中はAndroidへpendingとして残り、条件回復後に自動送信される。

### 2.2 実装するもの

- Android 26〜36のphoto permissionと、Android 14以降のfull / partial access表示。
- ユーザー操作による写真収集の有効化・無効化・partial accessの再選択。
- MediaStore Imagesのvolume別差分走査、初回baseline、cursor reset / volume着脱への対応。
- `DCIM/`配下にあり、MediaStoreで公開完了した画像のmetadata取得。
- 撮影時刻、source ID、ファイル名、original MIME、width / height、取得可能なEXIF緯度・経度。
- 最大辺512px、WebP lossy quality 65、回転補正済みthumbnail。
- Android app-private filesystemへのatomic保存とRoom version 3のpending / synced管理。
- 写真専用のperiodic collection、one-time collection、photo sync worker、stable unique name、lease、retry、run budget。
- `POST /api/v1/sync/photos` version 1 multipart contractと共通contract fixture。
- PCの`media_items` migration、thumbnail file store、hash / size / image validation、冪等な保存。
- thumbnail配信API、日付別Photos API、Timelineへのphoto union追加。
- Reactのphoto Timeline cardと、選択日のresponsive写真一覧。
- Android / Backend / Frontend / E2E / 実機の大量写真・障害復旧試験。
- README、上位設計、acceptance記録、backup対象説明の更新。

### 2.3 Phase 4に含めないもの

| 対象                                               | 実施時期・理由                                                                     |
| -------------------------------------------------- | ---------------------------------------------------------------------------------- |
| 写真原本のcopy、upload、backup、download           | 永続的な非目標。原本はAndroid / Google Photos等へ任せる                            |
| 動画、Motion Photo内のvideo部分、音声              | データ量とduration / codec / thumbnail規約が異なるため別Phaseで判断する            |
| Google Photos / cloud media providerからの自動収集 | Phase 9候補。Phase 4はlocal MediaStoreを対象にする                                 |
| 過去library全体の自動import                        | privacyと初回容量を優先し、full accessでは有効化後に追加された写真だけを対象にする |
| Camera folderをユーザーが追加・変更する設定        | Phase 7。Phase 4は`DCIM/`固定とし、対象をUIへ明記する                              |
| mobile network uploadを許可する設定                | Phase 7。Phase 4は安全な固定値`UNMETERED`にする                                    |
| 任意の同期間隔、画質、最大辺の設定                 | Phase 7。計測可能な固定値を先に完成させる                                          |
| 写真編集、削除、favorite、tag、album管理           | Phase 7以降                                                                        |
| 写真原本を開く、Android上の原本URIをPCへ渡す       | URIはPCで利用できず、原本非送信にも反する                                          |
| Map上の写真表示、LocationPointによる位置補完       | Phase 5。Phase 4はEXIF座標をnullableで保存するだけ                                 |
| 写真統計、Calendarの件数badge、検索                | Phase 7。`media_items`から追加できる保存・query境界だけを作る                      |
| thumbnailのBackup / Export UI                      | Phase 7。ただしPCのbackup対象に`thumbnails/`を含める規約は維持する                 |
| notification、foreground service、exact alarm      | 固定サイズ・分割batchをWorkManagerの通常workerで処理する                           |

## 3. 確定する振る舞い

### 3.1 permissionとopt-in

写真は機密性が高いため、permissionが付与されただけでは収集開始とみなさない。DataStoreの`photo_collection_enabled`はユーザーのopt-inだけを表し、実際のアクセス可否は毎回OSへ問い合わせる。

| OS / 状態                               | UI                               | collectorの動作                                                      |
| --------------------------------------- | -------------------------------- | -------------------------------------------------------------------- |
| Android 13以前で許可済み                | `写真: 有効（full）`             | baseline以後のDCIM画像を自動収集                                     |
| Android 14以降で`READ_MEDIA_IMAGES`許可 | `写真: 有効（full）`             | baseline以後のDCIM画像を自動収集                                     |
| Android 14以降でselected photosのみ     | `写真: 制限付き（選択済みのみ）` | 現在見える選択画像を明示importし、将来の未選択Camera画像は保証しない |
| denied / auto-reset                     | `写真: 権限が必要`               | cursor、既存pending、thumbnailを変更せず正常終了                     |
| opt-in OFF                              | `写真: 無効`                     | 新規収集を止める。既存pendingを勝手に削除しない                      |
| EXIF locationのみ拒否                   | `撮影地点: 未許可`               | 写真は収集し、latitude / longitudeを`null`にする                     |

permission requestは「写真の原本は送らず、thumbnailとmetadataをPCへ保存する」説明の直後に、ユーザーが押すbuttonから行う。partialからの再選択も必ずbutton操作で起動し、lifecycle復帰時にaccess状態を再評価する。

### 3.2 収集対象と初回baseline

- full accessでの自動対象はMediaStore Imagesのうち`DCIM/`配下の画像とする。Android 29以降は`RELATIVE_PATH`、26〜28はread-onlyのlegacy path情報をadapter内だけで使用する。partial accessでユーザーが明示選択した画像はfolderを問わず対象にできる。
- `IS_PENDING = 0`を確認できるOSでは、書込み中のmediaを除外する。trashed itemも除外する。
- original MIMEは`image/*`だけを許可する。生成物は常に静止画WebPであり、original拡張子を生成物へ引き継がない。
- full accessを初めて有効にした時点でvolumeごとのversion / generationまたは`DATE_ADDED + _ID`をbaselineとして記録し、既存libraryを自動importしない。
- permission dialog中に撮影された写真を落とさないよう、permission要求直前のopt-in開始時刻も保存する。許可直後に「開始時刻からbaseline確定時点まで」を一度走査してから現在generationをcursorへ保存し、schedule登録までを一つのCoordinatorから行う。
- partial accessでユーザーが選んだ既存写真は明示的な選択とみなし、baseline以前でもimportできる。`source_key`で再選択をdedupeする。
- 撮影時刻は正の`DATE_TAKEN`を優先し、欠損時だけ`DATE_ADDED * 1000`へfallbackする。PCとRoomではUTC epoch msとして保持する。
- `source_id` / `source_key`は`<volume-name>:<media-id>`とする。PCで一意にする範囲は`(device_id, source, source_id)`である。
- `MediaStore ID`だけでtimestamp由来IDを再生成せず、最初の発見時にULIDを一度作ってRoomへ固定する。

`DCIM/`は「Camera撮影」を厳密に証明する値ではない。OEMやCameraアプリが別folderへ保存する場合は対象外になり得る一方、DCIMへ保存された非Camera画像が含まれる場合もある。この制約をUIと受け入れ記録へ明記し、Phase 4で不透明なheuristicを増やさない。

### 3.3 原本と削除

- Androidはthumbnail生成と任意のEXIF読取りの間だけ`content://` URIを開き、原本byte列をRoom、app-private file、HTTP body、logへ保存しない。
- PCへ送るbinaryは生成済みWebPだけとする。request metadataにもAndroidのabsolute pathやcontent URIを含めない。
- PCがACKする前にAndroid原本が削除された場合、取得済みmetadataは`thumbnail_unavailable`としてmetadata-only同期できる。UIはplaceholderを表示し、「識別可能なthumbnail」の受け入れ件数には数えない。
- ACK済み写真のAndroid原本が後日削除されても、PCの`media_items`とthumbnailは残す。MediaStoreのdeleteをPC deleteへ変換しない。
- life-timelineからの明示削除はPhase 4に含めない。

### 3.4 workerと固定値

| 項目                       | 値                                               | 理由                                                                                |
| -------------------------- | ------------------------------------------------ | ----------------------------------------------------------------------------------- |
| periodic collection name   | `life_timeline_photo_collection_v1`              | UsageStats workerと独立した永続契約にする                                           |
| immediate collection name  | `life_timeline_photo_collection_now_v1`          | user triggerを重複enqueueしない                                                     |
| photo sync name            | `life_timeline_photo_sync_v1`                    | AppSessionのretry / constraintと分離する                                            |
| collection lease           | `photo_collection_v1`                            | periodic / immediate / manualを直列化する                                           |
| sync lease                 | `photo_sync_v1`                                  | 写真送信を一つにする                                                                |
| collection interval / flex | 15分 / 5分                                       | Phase 3の実測可能な周期を再利用する。期限の保証ではない                             |
| collection constraints     | `BatteryNotLow`、`StorageNotLow`                 | mediaはUsageEventsと違いOSに残るため、画像処理を低battery / 低storage時に遅延できる |
| sync constraints           | `UNMETERED`、`BatteryNotLow`、`StorageNotLow`    | 予期しないmobile dataと低storage時の一時file処理を避ける                            |
| backoff                    | exponential、初期30分                            | 写真binaryをAppSessionより低頻度で再送する                                          |
| thumbnail                  | 最大辺512px、WebP lossy、quality 65、upscaleなし | 上位仕様の60〜70を固定値にする                                                      |
| discovery上限              | 1 run 200 row                                    | Cursor保持時間とmemory利用を制限する                                                |
| thumbnail生成上限          | 1 run 20件                                       | Bitmap処理を分割する                                                                |
| upload batch               | 20件                                             | technical-designの初期目安を採用する                                                |
| per-file上限               | 1 MiB                                            | 512px WebPとして異常な生成物を遮断する                                              |
| request上限                | 20 files、20 MiB                                 | multipart abuseと一括memory使用を制限する                                           |
| 1 run上限                  | 8分または10 upload batch                         | WorkManager停止前にpendingを安全に残す                                              |
| lease TTL                  | 15分                                             | 8分budgetより長くし、process death後に回収可能にする                                |

値は`PhotoSyncPolicy`へ一元化する。実機計測で変更する場合はpolicy test、API limit、文書、受け入れ結果を同じPRで更新する。

## 4. Androidの保存・収集設計

### 4.1 Room version 3

`LifeTimelineDatabase`をversion 2から3へ上げ、既存5 tableを保持したまま次を追加する。

```text
android_media_items
--------------------------------
id                       TEXT PK          # Androidで生成したULID
source                   TEXT             # android_media_store
source_id                TEXT             # <volume>:<media-id>
volume_name              TEXT
media_store_id           INTEGER
filename                 TEXT
captured_at_ms           INTEGER
captured_at_source       TEXT             # date_taken / date_added_fallback
width                    INTEGER NULL
height                   INTEGER NULL
mime_type                TEXT
latitude                 REAL NULL
longitude                REAL NULL
thumbnail_state          TEXT             # pending / ready / unavailable / cleaned
thumbnail_relative_path  TEXT NULL
thumbnail_sha256         TEXT NULL
thumbnail_size_bytes     INTEGER NULL
sync_status              TEXT             # pending / synced
discovered_at_ms         INTEGER
synced_at_ms             INTEGER NULL
last_error_kind          TEXT NULL
```

制約とindex:

- `UNIQUE(source, source_id)`。
- `CHECK(thumbnail_state IN ('pending', 'ready', 'unavailable', 'cleaned'))`。
- `CHECK(sync_status IN ('pending', 'synced'))`。
- latitude / longitudeは両方`null`または両方非`null`。
- ready時はrelative path、SHA-256、sizeがすべて必要。cleaned時はsyncedでrelative pathが`null`、hash / sizeは監査用に保持する。
- `INDEX(sync_status, captured_at_ms, id)`と`INDEX(thumbnail_state, captured_at_ms, id)`。

```text
media_collection_state
-------------------------------
volume_name             TEXT PK
media_store_version     TEXT NULL
generation_cursor       INTEGER NULL
date_added_cursor_sec   INTEGER NULL
media_id_cursor         INTEGER NULL
collection_started_at_ms INTEGER
last_scan_at_ms         INTEGER NULL
updated_at_ms           INTEGER
```

`content://` URIやabsolute original pathは永続化しない。必要時に`volume_name`と`media_store_id`からURIを再構成する。app-private thumbnail pathはDBに絶対パスを保存せず、固定rootからのrelative pathだけを保存する。

`MIGRATION_2_3`とRoom schema JSONをcommitし、v1→v2→v3およびv2→v3の両方をinstrumentation testする。migrationで既存Session、cursor、open activity、background leaseを変更しない。

### 4.2 MediaStore差分走査

`MediaStorePhotoSource`はAndroid frameworkのCursorをdomain DTOへ変換し、RepositoryとworkerへCursorを漏らさない。

API 30以降:

1. `getExternalVolumeNames()`の現在mount済みvolumeを列挙する。
2. 保存した`media_store_version`と現在の`getVersion()`を比較する。
3. 同じversionなら`GENERATION_ADDED > cursor`を昇順で最大200件queryする。
4. versionが変わった場合はgenerationを信用せず、`collection_started_at_ms`以降を`DATE_ADDED + _ID`で再走査する。
5. batch内で取得した最大generationを、対象rowのRoom保存が完了した後だけcommitする。

API 26〜29:

1. `(DATE_ADDED > cursorDate) OR (DATE_ADDED = cursorDate AND _ID > cursorId)`を昇順でqueryする。
2. 秒精度・provider差に備え、直前の境界秒を再照合し、`UNIQUE(source, source_id)`でdedupeする。
3. rowのRoom保存後だけcursorを進める。

全versionで、row単位のSecurityException / IOException / unsupported imageは他rowから分離する。検出済みrowを先に`thumbnail_state=pending`で保存するため、一つの壊れた写真がcursorを塞がず、後続写真も処理できる。

partial accessでは、古い画像が権限の再選択によって新しく見える場合があり、`GENERATION_ADDED > cursor`だけでは検出できない。この状態では見えている選択集合を200件ずつ全照合し、既存`source_key`でdedupeする。選択集合から消えたrowは削除せず、PCへのdeleteも送らない。

volumeが一時的にunmountされた場合はstateを削除せず、次回mount時に続行する。full accessからpartial / deniedへ変わった場合も、見えなくなったsourceをPC削除とは解釈しない。

### 4.3 thumbnail生成

`PhotoThumbnailGenerator`の契約:

1. 再構成したMediaStore URIをread-onlyで開く。
2. API 29以降は`loadThumbnail(uri, Size(512, 512), cancellationSignal)`を使う。
3. API 26〜28はboundsを先に読み、`inSampleSize`でbounded decodeし、ExifInterfaceのorientationを反映する。
4. 長辺が512pxを超える場合だけaspect ratioを保って縮小し、upscaleしない。
5. API 30以降は`WEBP_LOSSY`、26〜29は`WEBP`、quality 65でencodeする。
6. encode結果を1 MiB以下、width / heightを各1〜512、WebP signatureありとして自己検証する。
7. SHA-256とbyte数を計算してRoomへ保存する。
8. Bitmap、stream、temporary fileを`finally` / `use`で解放し、cancellationを握り潰さない。

EXIF位置はthumbnail生成とは独立したbest-effort処理にする。権限があり、original MIMEをExifInterfaceが読める場合だけ`setRequireOriginal()` URIをstreamで読み、緯度・経度の両方が有限かつ範囲内なら保存する。失敗してもthumbnailを失敗扱いにしない。

### 4.4 Android側fileのatomic保存とcleanup

保存rootは`filesDir/photo-thumbnails/`とし、final pathを`<idの先頭2文字>/<id>.webp`で分散する。

```text
encode to <id>.webp.tmp-<token>
  ↓ flush / close
size + hash検証
  ↓ same directory内でrename
final <id>.webp
  ↓ Room transaction
thumbnail_state = ready
```

final fileが先、Room参照が後であるため、DBが存在しないfileをreadyとして指さない。crashで残るtemp / orphan finalは、24時間より古く、かつRoomから参照されないものだけを次回workerが削除する。広いdirectoryや原本URIをcleanup対象にしない。

PC ACK後は一つのRoom transactionで対象rowをsyncedかつ`thumbnail_state=cleaned`へ更新し、relative pathを切り離してからAndroidの一時thumbnailを削除する。hash / sizeは監査用に保持する。DB更新後・file削除前にcrashした場合は参照されないorphanになるため、次回の限定cleanupで回収できる。削除失敗でsyncedをpendingへ戻さない。未ACK、unknown ACK、cancellationでは状態もfileも変更しない。

## 5. Photo Sync API version 1

### 5.1 wire contract

endpointは次とする。

```http
POST /api/v1/sync/photos
Content-Type: multipart/form-data
```

multipartはJSON part `metadata`を1個と、ready写真ごとのbinary partを最大20個持つ。binary part nameは`thumbnail_<photo-id>`で固定し、client filenameやpathを保存先決定に使わない。metadata-only itemにはbinary partを付けない。

```json
{
  "schemaVersion": 1,
  "device": {
    "id": "01...ULID...",
    "name": "Android device",
    "platform": "android"
  },
  "photos": [
    {
      "id": "01...ULID...",
      "source": "android_media_store",
      "sourceId": "external_primary:12345",
      "filename": "IMG_0001.jpg",
      "capturedAtMs": 1789052400000,
      "width": 4032,
      "height": 3024,
      "mimeType": "image/jpeg",
      "latitude": null,
      "longitude": null,
      "thumbnail": {
        "mimeType": "image/webp",
        "width": 512,
        "height": 384,
        "byteSize": 48231,
        "sha256": "64 lowercase hex characters"
      }
    }
  ]
}
```

responseは既存同期と同じenvelopeにする。

```json
{
  "schemaVersion": 1,
  "accepted": ["01...ULID..."]
}
```

`contracts/sync/photos-v1.json`へ合成metadataと小さな合成WebP fixtureを追加し、Android serializerとBackend parserの双方から検証する。fixtureは実写真、EXIF位置、実端末情報を含めない。

### 5.2 validationと上限

- `schemaVersion`はliteral 1、unknown fieldを拒否する。
- requestは1〜20 photo、ID / acceptedはULID、deviceはAndroidに限定する。
- filenameは空白だけ、control文字、`/`、`\`を拒否し最大255文字とする。server側でsilent sanitizeせず、PC pathには一切使わない。
- `source`は`android_media_store`、`sourceId`は最大255文字とし、request内重複を拒否する。
- `capturedAtMs >= 0`、original width / heightはnullableな正数、MIMEは最大100文字の`image/*`。
- latitudeは-90〜90、longitudeは-180〜180で、片方だけの値を拒否する。
- thumbnail metadataがあるitemには対応するfileをちょうど1個要求し、ないitemへの余分なfileも拒否する。
- fileは宣言sizeと実size、宣言SHA-256と実hashを一致させる。
- WebP decode後のformatとpixel sizeを検証し、最大辺512、各辺1以上とする。metadataだけを信用しない。
- 1 file 1 MiB、合計20 MiBをincremental read中に強制し、超過時はHTTP 413と安全な`payload_too_large` envelopeを返す。
- multipart parsingに必要な`python-multipart`と、WebP検証用Pillowのversionを`pyproject.toml` / `uv.lock`へ固定し、実装時に公式releaseと脆弱性情報を確認する。[FastAPI file upload](https://fastapi.tiangolo.com/tutorial/request-files/)

### 5.3 ACK、冪等性、競合

- request全体を検証してから保存を開始する。validation、hash、decodeの一つでも失敗した場合はacceptedを返さない。
- 同じID・同じ正規化metadata・同じthumbnail hashの再送は成功し、同じIDをacceptedへ返す。
- 同じIDでmetadataまたはhashが異なる場合は409 `sync_conflict`とする。
- 同じ`(device_id, source, source_id)`が別IDで届いた場合も409とし、silent mergeしない。
- metadata-onlyで保存済みのitemへ、同じIDで後からthumbnailを補完する更新だけを許可する。既存thumbnailを異なるhashで上書きしない。
- acceptedはrequest順、重複なし、request内IDのsubsetとする。Androidはunknown / duplicate / missing ACKをprotocol errorにし、未確認itemをpendingのまま残す。
- PC保存後にresponseが切れた場合はAndroidが同じIDとhashを再送し、PC側で件数を増やさずacceptedを再返却する。

## 6. PC Backendとfilesystem

### 6.1 migrationと`media_items`

Alembic `0002`で次を追加する。`media_items`は将来videoを表現できる上位modelを維持するが、Phase 4 APIは`type=photo`だけを作る。

```text
media_items
--------------------------------
id                    TEXT PK
device_id             TEXT FK devices.id
type                  TEXT             # photo
source                TEXT             # android_media_store
source_id             TEXT
filename              TEXT
captured_at_ms        INTEGER
width                 INTEGER NULL
height                INTEGER NULL
duration_ms           INTEGER NULL
latitude              REAL NULL
longitude             REAL NULL
thumbnail_path        TEXT NULL         # data_dirからのrelative path
thumbnail_mime_type   TEXT NULL
thumbnail_width       INTEGER NULL
thumbnail_height      INTEGER NULL
thumbnail_size_bytes  INTEGER NULL
thumbnail_sha256      TEXT NULL
mime_type             TEXT
created_at_ms         INTEGER
```

制約:

- `CHECK(type IN ('photo', 'video'))`だがPhase 4 insertはphotoのみ。
- `UNIQUE(device_id, source, source_id)`。
- captured time index。
- location pair、thumbnail一式、正のdimension / sizeのcheck。
- `thumbnail_path`は絶対パスではなく、`thumbnails/`からのPOSIX風relative path。

`Settings`へ`thumbnail_dir` propertyを追加し、data directory解決以外から保存先を注入できるようにする。API testでは毎回一時data directoryを使い、通常利用のthumbnailへ触れない。

### 6.2 atomicなfile / DB保存

`ThumbnailStore`と`MediaRepository`を分け、routeから直接path操作しない。

1. 全partをrequest専用temporary directoryへsize上限付きでstream保存する。
2. hash、WebP decode、pixel size、metadata対応を全件検証する。
3. final pathをserver側で`YYYY/MM/DD/<id>.webp`に決める。年月日は`captured_at_ms`のUTC日付から決め、Timelineの表示timezoneとは独立した内部配置とする。
4. 同じfilesystem上のtemporary fileをfinal directoryへatomic renameする。既存同一hash fileは再利用し、異なるhashは409にする。
5. final fileが全件存在することを確認してからSQLite transactionでdeviceとmedia itemを保存する。
6. DB commit失敗時は、このrequestが新規作成したfinal fileだけを削除する。既存fileは削除しない。
7. process crashで残ったorphanは、DB参照がなく十分古いことを確認するmaintenance test / 将来cleanup対象とし、広い自動削除はPhase 4 runtimeへ入れない。

この順序により、通常の例外経路でDBが存在しないthumbnailを指す状態を作らない。filesystemはSQLite transactionへ参加できないため、process crash時のorphan fileまでは許容し、DB参照切れより安全な側へ倒す。

### 6.3 thumbnail配信

```http
GET /api/v1/media/{media_id}/thumbnail
```

- path parameterをULIDとして検証し、DBからthumbnail metadataを取得する。
- client入力をfilesystem pathへjoinしない。DBのrelative pathをthumbnail root配下へresolveし、root外なら500の安全なerrorにする。
- metadata-only / missing itemは404、DB参照file欠損は安全な500とし、absolute pathをmessageへ出さない。
- responseは`image/webp`、`X-Content-Type-Options: nosniff`を付ける。
- private local dataのため共有cacheを避け、初期値は`Cache-Control: private, max-age=86400`とする。IDに対するthumbnailはimmutableであり、更新時は同一hashだけを許す。
- `thumbnails/` directory全体のStaticFiles mountやdirectory listingは行わない。

## 7. Query APIとFrontend

### 7.1 Timeline contract

既存`TimelineItem`をdiscriminated unionへ変更する。

```ts
type TimelineItem = AppSessionTimelineItem | PhotoTimelineItem;

interface PhotoTimelineItem {
  type: "photo";
  id: string;
  deviceId: string;
  deviceName: string;
  source: "android_media_store";
  takenAt: string;
  filename: string;
  mimeType: string;
  width: number | null;
  height: number | null;
  latitude: number | null;
  longitude: number | null;
  thumbnailUrl: string | null;
}
```

photoはintervalではなくpoint eventなので`TimelineDisplay`を持たない。日付範囲は既存`build_day_range()`で作り、`rangeStart <= captured_at < rangeEnd`で検索する。AppSessionとphotoを表示timestamp、type、IDの安定順でmergeし、reloadで順番を変えない。

### 7.2 Photos API

```http
GET /api/v1/photos?date=2026-09-12&timezone=Asia%2FTokyo
```

responseはTimelineと同じdate / timezone / rangeStart / rangeEndと、`PhotoTimelineItem`相当の`items`を持つ。captured_at降順、ID降順で写真一覧向けに返す。paginationはPhase 4では日次件数を実測し、必要ならPhase 7でcursor方式を追加する。無制限の全期間一覧APIは作らない。

### 7.3 React UI

- API typeをunion化し、`TimelineItem`内でtypeごとのcomponentへ分岐する。`app_session`固有fieldへphotoから触れない。
- photo cardは撮影時刻、thumbnailまたはplaceholder、ファイル名、撮影端末、original dimensionを表示する。
- 画像は`loading="lazy"`、明示width / height、意味のあるalt textを持ち、layout shiftを抑える。
- 選択日の写真一覧をresponsive gridで表示し、0件、loading、API error、thumbnail 404を区別する。
- thumbnail URLはAPIが返すsame-origin relative URLを使用し、clientがpathを組み立てない。
- EXIF位置はPhase 4では数値を前面表示せず、「撮影地点あり」程度に留める。Map linkはPhase 5で追加する。
- 原本を開くbutton、download、lightboxの原寸表示は追加しない。thumbnail拡大時も512px WebPだけを使う。
- DashboardのApp usage統計は変更しない。photo件数をapp countへ混ぜない。

## 8. retry、failure分類、状態遷移

| 条件                                       | Android状態                               | Worker結果       | 次の動作                                 |
| ------------------------------------------ | ----------------------------------------- | ---------------- | ---------------------------------------- |
| opt-in OFF                                 | 既存dataを変更しない                      | `success`        | userが有効化するまで収集しない           |
| permission denied                          | cursor / pending維持                      | `success`        | UIで設定要確認、次周期で再確認           |
| partial access                             | 見える選択itemだけ処理                    | `success`        | UIで制限を表示し、再選択可能にする       |
| MediaStore一時Unavailable / volume unmount | 該当volume cursor維持                     | `retry`          | backoff後に再走査                        |
| unsupported / 壊れた1画像                  | itemを`unavailable`、他item継続           | `success`        | metadata-only同期、error件数表示         |
| local storage low                          | ready前のitemをpending維持                | constraint待ち   | storage回復後に生成                      |
| sync対象0件                                | 変更なし                                  | `success`        | 次triggerを待つ                          |
| 全件ACK                                    | acceptedをsynced、local thumbnail cleanup | `success`        | 完了                                     |
| 部分ACK後の一時障害                        | ACK済みだけsynced、残りpending            | `retry`          | 古い残件から再開                         |
| DNS / TLS / timeout / connection           | 未ACKとfileを保持                         | `retry`          | exponential backoff                      |
| HTTP 408 / 429 / 5xx                       | 未ACKとfileを保持                         | `retry`          | exponential backoff                      |
| HTTP 409 / 422 / 413 / その他4xx           | 未ACKとfileを保持                         | `failure`        | 即時loopせず、protocol / payload修正待ち |
| hash / unknown ACK / duplicate ACK         | 未確認itemを保持                          | `failure`        | protocol error表示                       |
| endpoint未設定                             | pending保持                               | `success`        | URL保存時に再enqueue                     |
| metered network                            | pending保持                               | constraint待ち   | unmeteredへ戻るまで送らない              |
| cancellation / process death               | ACK済みまで反映、未ACK保持                | cancellation伝播 | WorkManager再schedule、stale lease回収   |
| run budget到達                             | 処理済み分だけ確定                        | `retry`          | 残件から続行                             |

photo collectionとphoto syncは別leaseを使う。syncはready rowとfileをsnapshotして送信するため、collectionが新しいrowを追加しても送信中batchを変更しない。ACK反映はIDとexpected hashを条件に行い、`cleaned`へ遷移する直前に退避した固定root配下のpathだけを削除対象にする。

## 9. Android UIと安全な診断

既存Compose画面へ次を追加する。

- 写真収集: `無効` / `full` / `選択済みのみ` / `権限が必要`。
- 「写真収集を有効にする」「写真の選択を変更」「写真収集を無効にする」。
- 対象が`DCIM/`、full access時は有効化後の新規写真のみである説明。
- 写真の最終scan試行 / 成功時刻、最終同期試行 / 成功時刻。
- thumbnail生成待ち件数、photo pending件数、app-private一時file合計byte数。
- `unmetered待ち`、`battery待ち`、`storage待ち`、`permission`、`source_unavailable`、`network`、`server`、`protocol`の安全な分類。
- 「写真を今すぐ確認」はunique one-time collectionをenqueueし、既存periodic workをcancel / replaceしない。
- 既存の「収集して同期」はAppSessionの即時診断として残し、写真のmobile data送信を暗黙に開始しない。

画面、log、test artifactにはfilename、content URI、source ID、hash、座標、thumbnail、実hostnameを出さない。件数、byte合計、経過時間、result codeだけを診断値とする。

## 10. 実装タスクと依存関係

各IDは原則1 Pull Request程度とし、親Issue `Phase 4: Photos`の子Issueとして管理する。

```text
P4-01 Photo v1 contract・policy・合成fixture
  ├─→ P4-02 Backend media schema・thumbnail store・Sync API ──┐
  └─→ P4-03 Android permission・MediaStore adapter             │
               ↓                                               │
        P4-04 Room v3・thumbnail生成・local file管理             │
               ↓                                               │
        P4-05 photo collection / sync worker・UI診断             │
               └───────────────────────────────────────────────┤
                                                               ↓
                            P4-06 Timeline / Photos / thumbnail API
                                                               ↓
                            P4-07 React Timeline・写真一覧
                                                               ↓
                            P4-08 統合test・CI gate
                                                               ↓
                            P4-09 大量写真・障害復旧の実機受け入れ
                                                               ↓
                            P4-10 文書更新・Phase 5引き継ぎ
```

### P4-01: Photo v1 contractと固定policyを定義する

- **目的:** AndroidとBackendを独立に実装してもwire / size / hash規約が一致するようにする。
- **依存:** Phase 3の実装済みscheduler / WorkerFactory / lease / retry契約。
- **作業:** §3.4と§5のPydantic / Kotlin DTO相当、multipart part名、合成JSON / WebP fixture、error envelope、policy定数を追加する。ExifInterface、python-multipart、Pillowのstable versionを確認する。
- **成果物:** `contracts/sync/photos-v1.json`、合成WebP、contract test、`PhotoSyncPolicy`。
- **完了条件:** 両言語で同じfixtureを読め、unknown field、hash不一致、file対応不一致、上限超過を拒否できる。

### P4-02: Backendのmedia schema、file store、Photo Sync APIを実装する

- **目的:** photo metadataとthumbnailをPCへ冪等かつ安全に保存する。
- **依存:** P4-01。
- **作業:** Alembic 0002、`MediaItem`、Repository、ThumbnailStore、multipart route、stream limit、WebP検証、atomic rename / rollback cleanupを実装する。
- **成果物:** `POST /api/v1/sync/photos`、`media_items`、`thumbnails/`保存。
- **完了条件:** 新規、同一再送、ACK切断後再送、metadata-only補完、409 conflict、SQLite busy、file write失敗をtestし、DBがmissing fileを指さない。

### P4-03: Android permissionとMediaStore adapterを実装する

- **目的:** OS versionとprivacy設定を尊重して新しい写真を安定検出する。
- **依存:** P4-01。
- **作業:** manifest / ActivityResult permission、full / partial / denied判定、volume列挙、generation / legacy cursor query、DCIM filter、baseline、version resetを実装する。
- **成果物:** `PhotoAccessChecker`、`MediaStorePhotoSource`、permission UIの最小入口。
- **完了条件:** API 26 / 29 / 30 / 33 / 34 / 36の分岐をtestし、partialをfullと誤表示せず、初回fullで過去libraryをimportしない。

### P4-04: Room v3とthumbnail pipelineを実装する

- **目的:** 発見済み写真を個別に再開可能な状態へし、原本を残さず軽量previewを作る。
- **依存:** P4-03。
- **作業:** §4の2 entity / DAO、MIGRATION_2_3、scan transaction、bounded decode、orientation、WebP、EXIF location、hash、atomic local store、orphan temp cleanupを実装する。
- **成果物:** Room version 3、PhotoCollectionRepository、PhotoThumbnailGenerator / Store。
- **完了条件:** 1画像の破損で後続cursorが止まらず、最大辺 / quality / path / hash規約を満たし、原本byteやabsolute pathを永続化しない。

### P4-05: 写真の自動収集・自動同期とAndroid UIを完成する

- **目的:** 画面操作なしでpendingを蓄積・送信し、利用者が状態を理解できるようにする。
- **依存:** P4-02、P4-04。
- **作業:** photo worker 2種、immediate trigger、unique work、constraints、lease、multipart OkHttp upload、retry mapping、run budget、ACK cleanup、Compose診断を実装する。
- **成果物:** Phase 3と独立したphoto schedule / sync経路、permission・pending・storage表示。
- **完了条件:** metered / PC停止中はfileを保持し、unmetered / PC復旧後に古い順で送り、AppSession workerやmanual操作と干渉しない。

### P4-06: Timeline、Photos、thumbnail配信APIを実装する

- **目的:** 同じ`media_items`から時系列と一覧の両方を返す。
- **依存:** P4-02。
- **作業:** Pydantic union、media query、安定merge sort、`GET /timeline`拡張、`GET /photos`、ID経由thumbnail responseを実装する。
- **成果物:** version 1 read APIとOpenAPI contract。
- **完了条件:** timezone日境界、空日、同時刻、metadata-only、missing file、path traversalをtestし、既存AppSession responseを壊さない。

### P4-07: Reactのphoto Timelineと写真一覧を実装する

- **目的:** 選択日に撮った写真をPC上で識別できるようにする。
- **依存:** P4-06。
- **作業:** TypeScript union、photo card、lazy thumbnail、placeholder、日次grid、loading / empty / error、responsive CSS、component testを追加する。
- **成果物:** Timeline内photo表示とPhotos一覧。
- **完了条件:** keyboard / screen readerで内容を識別でき、thumbnail failureがTimeline全体を壊さず、日付変更で古いresponseを表示しない。

### P4-08: end-to-end testとCI gateを完成する

- **目的:** MediaStoreからPC UIまでの境界をPRで回帰検出する。
- **依存:** P4-05〜07。
- **作業:** 合成画像によるBackend / Android contract、Room / WorkManager instrumentation、実DB Playwright E2E、migration、file cleanup、artifact safetyを既存workflowへ統合する。
- **成果物:** 既存6 required checks内のPhoto testと、失敗時の安全な要約。
- **完了条件:** test 0件やskipを成功扱いせず、合成fixtureだけでthumbnail upload・配信・表示をCI確認できる。

### P4-09: 大量写真と障害復旧を実機で受け入れる

- **目的:** OEM MediaStore、実画像codec、memory、battery、Tailscale、再起動を実測する。
- **依存:** P4-08。
- **作業:** §12のシナリオを専用PC DBと検証用写真で実施し、件数、合計byte、最大処理時間、pending推移、battery参考値、復旧時間だけを記録する。
- **成果物:** `docs/development/phase4-acceptance.md`。
- **完了条件:** AC-01〜22を満たし、通常撮影後に原本なしのthumbnailがPC Timeline / 写真一覧へ一度だけ表示される。

### P4-10: 文書を更新しPhase 5へ引き継ぐ

- **目的:** 実装値と上位文書を一致させ、位置情報追加時の境界を残す。
- **依存:** P4-09。
- **作業:** README、overview、product-spec、architecture、technical-design、data-model、implementation-plan、toolchainsを実測値へ更新する。thumbnail backup対象と復旧手順も記載する。
- **成果物:** 日常運用手順、容量・障害切り分け、Phase 5へのEXIF / MediaItem query引き継ぎ。
- **完了条件:** 計画値ではなく実測済みの挙動が記載され、写真原本も同期するように読める記述がない。

## 11. テスト・CI計画

### 11.1 Android pure unit test

- permission matrixがAPI 26 / 29 / 33 / 34 / 36でfull / partial / deniedを正しく返す。
- full access baseline、partial選択import、generation継続、version reset、legacy複合cursor。
- DCIM / non-DCIM、pending / trashed、timestamp fallback、複数volume、同一source dedupe。
- dimension計算が縦長・横長・正方形・512以下でaspect ratioを維持し、upscaleしない。
- API 30前後のWebP format選択とquality 65。
- EXIFあり / なし / permissionなし / 不正座標で写真処理自体は継続する。
- multipart part名、JSON、hash、size、20件batch、metadata-only item。
- transient / permanent分類、unknown ACK、partial ACK、run budget、cancellation伝播。
- UI stateがopt-in、permission、constraint、pending、unavailableを区別する。

framework Cursor / Bitmapの実挙動をlocal JVMだけで断定せず、adapterのmappingはfakeでunit testし、実APIはinstrumentationへ置く。

### 11.2 Room / Android instrumentation test

- v1 fixture→v2→v3とv2 fixture→v3で既存全tableを保持する。
- source unique、pending順、ready / cleaned invariant、ACK conditional update、synced後cleanup対象取得。
- local temp→final→Room参照、各段階の例外、orphan temp cleanup、別ID fileの非削除。
- Emulator MediaStoreへ合成JPEG / PNGをinsertし、scan、512px WebP、orientation、Room保存を確認する。
- full / deniedはautomationし、partial permissionのOS dialog選択は可能な範囲をtestし、残りを実機受け入れへ送る。
- periodic / immediate collectionを近接実行してleaseにより一度だけ処理する。
- photo syncはUNMETERED / BatteryNotLow / StorageNotLowが揃うまでRUNしない。
- UsageStats periodic workとphoto periodic workがそれぞれ一つで、互いをreplaceしない。
- process再生成、DB close / reopen、stale lease、worker stop後も未ACK fileが残る。

### 11.3 Backend test

- migration 0001→0002、fresh head、downgrade可能範囲、index / constraint。
- multipart 1件 / 20件、metadata-only、empty、21件、1 MiB境界、総量超過。
- invalid ULID、unknown field、duplicate ID / sourceId、location片側、範囲外、pathを含むfilename。
- missing / extra / duplicate part、偽MIME、WebPでないbyte、pixel過大、hash / size / dimension不一致。
- 新規保存、同一再送、部分重複、別内容同一ID、別ID同一source、metadata-onlyからthumbnail補完。
- temp write / rename / DB commit失敗で既存fileを消さず、DB参照切れを残さない。
- SQLite busyを503へ変換し、Androidがretry分類できる。
- thumbnail GETの正常、404、DB参照file欠損、invalid ID、root外path、header。
- Timeline / Photosの日付、timezone、DST、UTC midnight、同時刻安定sort。
- AppSessionだけの既存fixture responseと統計が回帰しない。

### 11.4 Frontend test / E2E

- TypeScript unionのapp session / photo分岐。
- photo cardのthumbnail、placeholder、alt、filename、時刻、device。
- galleryのloading、empty、API error、image load error、日付変更、stale response abort。
- 縦長 / 横長 / 20件以上のresponsive layoutとlazy loading。
- 合成photoをSync APIへ送り、実SQLite / filesystem / APIを経てTimelineと写真一覧へ一度だけ表示する。
- 同じpayload再送後もDOM item数が増えない。
- 翌日、前日、timezone変更で対象写真だけが表示される。
- app statisticsがphoto追加で変わらない。

### 11.5 CI gate

- `frontend-ci`
- `backend-ci (ubuntu)`
- `backend-ci (windows)`
- `android-ci`
- `pc-core-e2e`
- `android-instrumentation-ci`

既存6 checksをrequiredのまま維持する。Android unitは`android-ci`、MediaStore / Room / WorkManagerは`android-instrumentation-ci`、実SQLiteとthumbnail配信を使うbrowser確認は`pc-core-e2e`へ入れる。失敗artifactはJUnit、件数、byte数、result codeだけにし、画像本体、filename、source ID、hash、座標、URI、path、hostnameを含めない。

## 12. 実機受け入れ手順と完了条件

### 12.1 専用環境

1. clean checkoutで既存6 checks相当を実行する。
2. `%TEMP%`配下の一意なPhase 4専用`LIFE_TIMELINE_DATA_DIR`へmigrationを適用する。
3. Backendを`127.0.0.1:8000`、Frontendを`127.0.0.1:5173`で起動する。
4. FunnelなしのTailscale Serveを設定し、実URLを文書やartifactへ保存しない。
5. debug APKをupgrade installし、既存Room v2 dataを保持したv3 migrationを確認する。
6. 検証用として撮影内容に個人情報・顔・住所・画面・文書を含まない写真を用意する。
7. 写真収集を有効化し、full / partial / deniedとEXIF location許可の状態を確認する。
8. PCの`thumbnails/`とAndroid app-private fileは内容を開示せず、件数とbyte数だけを記録する。

### 12.2 代表シナリオ

1. full access有効化前の既存DCIM写真が自動importされないことを確認する。
2. 有効化後に縦長・横長・位置あり / なしの検証用写真を撮り、画面を閉じたまま自動収集する。
3. Android側WebPの最大辺、件数、合計byteを確認し、original file sizeがHTTP送信量に現れないことを確認する。
4. unmetered networkで自動同期し、PC Timelineと写真一覧へ正しい日付・時刻・向きで一度だけ表示する。
5. Android 14以降でpartial accessへ変更し、選択写真だけが見え、状態が`full`にならないことを確認する。
6. permissionを取消し、cursorと既存pendingが維持され、再許可後に続行することを確認する。
7. PC / FastAPI / Tailscaleを停止して複数写真を撮り、pending fileが残ることを確認する。
8. metered networkへ切り替え、AppSessionは既存方針で同期できてもphotoは送信待ちになることを確認する。
9. PCとunmetered networkを復旧し、手動操作なしに古い写真から送られることを確認する。
10. upload中にprocess killし、ACK前fileが残り、stale lease回収後に同じID / hashで再送されることを確認する。
11. Androidを再起動し、photo periodic workとretry中workが復旧することを確認する。
12. 1回に20枚を超えるburst撮影を行い、複数batchで欠落・重複・memory異常なく同期することを確認する。
13. ACK済みの検証用原本をAndroidから削除し、PCの記録とthumbnailが残ることを確認する。
14. thumbnail生成前に原本を削除する別ケースでmetadata-only placeholderとなり、queue全体が停止しないことを確認する。
15. 24時間以上の通常scheduleで新規写真、pending推移、battery / storage参考値、復旧時間を記録する。

### 12.3 受け入れチェックリスト

| ID    | 完了条件                                                                           | 主な証拠                     |
| ----- | ---------------------------------------------------------------------------------- | ---------------------------- |
| AC-01 | Phase 3の正常系受け入れと既存6 required checksが回帰していない                     | Phase 3記録、CI              |
| AC-02 | API level別permissionを使い、Android 14以降のpartialをfullと誤認しない             | unit / 実機permission試験    |
| AC-03 | full access初回に過去libraryをimportせず、有効化後のDCIM写真を収集する             | baseline test、実機件数      |
| AC-04 | partial accessでは明示選択写真だけをimportし、再選択入口がある                     | API 34+実機                  |
| AC-05 | volume / generation / legacy cursorで再走査しても同一sourceが同じULID・1件になる   | adapter / Room test          |
| AC-06 | thumbnailは正しい向き、最大辺512px以下、WebP quality 65で、原本を保存・送信しない  | generator test、送信byte確認 |
| AC-07 | EXIF位置は許可時だけpairで保存し、拒否・欠損でも写真同期できる                     | permission / EXIF test       |
| AC-08 | Room v3 migrationが既存AppSession・cursor・leaseを保持する                         | migration test               |
| AC-09 | crash各段階でready rowがmissing local fileを指さず、未ACK fileを失わない           | fault injection test         |
| AC-10 | photo workはUsageStats workと別名・別leaseで一つずつ登録される                     | WorkManager integration      |
| AC-11 | photo uploadはUNMETERED / BatteryNotLow / StorageNotLowでのみ開始する              | constraint / 実機切替        |
| AC-12 | PC停止、Tailscale切断、metered network中もmetadataとthumbnailがpendingで残る       | 障害試験、件数・byte数       |
| AC-13 | 復旧後に20件batchで古い順に自動同期し、部分ACK後は残件から続行する                 | worker / 実機復旧            |
| AC-14 | APIがfile数・byte数・hash・WebP・pixel・metadata対応をserver側で検証する           | Backend API test             |
| AC-15 | 同一ID / hash再送でPC DB件数、created_at、file数が増殖しない                       | API / E2E / 実機             |
| AC-16 | file / DB保存失敗でDB参照切れや既存thumbnail消失を残さない                         | fault injection test         |
| AC-17 | AndroidでACK済み原本を削除してもPC metadata / thumbnailが残る                      | 実機削除試験                 |
| AC-18 | ACK後だけAndroid rowをcleanedへ遷移して一時thumbnailを削除し、失敗時は誤削除しない | Repository / filesystem test |
| AC-19 | TimelineがphotoとAppSessionをtimezone日範囲で安定順に表示する                      | API / E2E                    |
| AC-20 | Photos APIとresponsive一覧がthumbnail / placeholder / empty / errorを表示する      | component / E2E              |
| AC-21 | thumbnail APIがID経由だけでWebPを返し、path traversalやdirectory公開を許さない     | security test                |
| AC-22 | 20枚超burst、再起動、process kill、24時間運転で欠落・重複・memory異常がない        | Phase 4実機受け入れ記録      |

AC-01〜22、全required checks、専用実機データでの長時間・大量写真試験をもってPhase 4を完了する。

## 13. セキュリティとprivacy確認

- FastAPIのloopback bind、Tailscale Serve HTTPS、Funnel未使用、最小ACLを維持する。
- 写真同期のためにLAN bind、cleartext traffic、TLS検証無効化、独自tokenを追加しない。
- full photo accessは広い権限であることを事前説明し、denied / partialでもアプリ利用を妨げない。
- 動画権限、位置情報継続取得権限、background locationは要求しない。
- EXIF location permissionは写真の位置metadata読取りだけに使い、端末位置を取得しない。
- original path / URI / bytesをAndroid DB、HTTP、PC、logへ残さない。
- multipartのclient filename、original filename、source IDをpathとして利用しない。
- thumbnailは画像としてdecode検証し、size / pixel上限をdecode前後で強制する。
- API errorはID、filename、hash、path、座標、hostnameを返さず、fieldと分類だけを返す。
- 合成fixture以外のthumbnail、raw DB、HTTP capture、logcatをCI artifactやIssueへ添付しない。
- PCの`lifelog.db`と`thumbnails/`は同じprivate data rootとして扱い、片方だけのbackupを完全backupと呼ばない。

## 14. 非保証と運用上の注意

- 15分は実行期限ではない。Doze、OEM最適化、battery / storage constraint、force-stopにより遅延する。
- `UNMETERED`は通常Wi-Fiを意味するが、OSがunmeteredと判定する他networkも含み、特定SSIDやTailscale到達性を保証しない。
- partial accessは将来撮影される全写真を自動収集できない。自動収集の完成条件はfull accessの検証を基準にする。
- OEM CameraがDCIM外へ保存する画像、MediaStoreへ登録されない画像、Secure Folder / work profile / 別userの画像は対象外になり得る。
- MediaStoreの撮影時刻が欠損して`DATE_ADDED`へfallbackした写真は、実際の撮影日ではなく追加日に表示され得る。
- 原本がthumbnail生成前に消えた場合、Phase 4は画像previewを復元できない。metadata-only記録を残す。
- Android app data消去 / uninstall、permission取消中に見えなかったpartial写真、OSがMediaStore recordを失った期間は復元できない。
- PC側のthumbnail削除・filesystem破損はAndroid synced rowから自動修復しない。Backup / repairはPhase 7で扱う。
- Phase 4のPhotos一覧は日単位でpaginationなしとし、極端な件数へのpaginationは計測後に追加する。

## 15. リスクと対策

| リスク                                        | 対策                                                          | 受け入れでの確認       |
| --------------------------------------------- | ------------------------------------------------------------- | ---------------------- |
| full permissionで過去libraryを大量取得する    | enable時baseline、過去import非対応                            | 既存写真件数が増えない |
| partial accessをfullと誤認し欠落に気付けない  | OS状態を毎回判定し明示表示                                    | API 34+切替試験        |
| MediaStore generationがresetする              | version照合、開始時刻から再走査、source dedupe                | version変更fake        |
| ID再利用やvolume差で別写真を混同する          | volume + media ID source key、PC unique制約、content conflict | conflict test          |
| 巨大画像decodeでOOMになる                     | loadThumbnail / sample decode、20件budget、cancellation       | 高解像度合成画像       |
| EXIF orientationが無視される                  | API別thumbnail adapterと実機縦写真                            | pixel / visual確認     |
| location権限拒否で全処理が止まる              | EXIFをbest-effort nullableに分離                              | permission拒否試験     |
| local storageをpending thumbnailが圧迫する    | 512px / 1 MiB上限、StorageNotLow、ACK後cleanup、byte表示      | PC長期停止試験         |
| mobile dataを大量消費する                     | photo syncをUNMETERED固定                                     | network切替試験        |
| multipart全体をmemoryへ読む                   | UploadFile / stream copy、part / byte上限                     | 20 MiB境界test         |
| file成功・DB失敗で不整合になる                | final file先行、transaction後参照、限定cleanup                | fault injection        |
| ACK消失でfileを上書き・重複する               | ID + hash冪等性、既存同一file再利用                           | response切断再送       |
| source削除がPC履歴を消す                      | delete非伝播                                                  | Android原本削除試験    |
| filename / image / locationがartifactへ漏れる | 合成fixture、safe aggregate diagnostics                       | artifact review        |
| photo workerがAppSession workerをreplaceする  | stable name / lease / dependencyを完全分離                    | WorkManager同居test    |
| 壊れた1画像がcursorを永久停止する             | discovered rowを先に保存しitem単位でunavailable化             | corrupt image test     |
| thumbnail routeから任意fileを読める           | ULID lookup、root containment、directory非公開                | traversal test         |

## 16. Phase 5への引き継ぎ

Phase 5のLocation実装では、Phase 4の`media_items.latitude / longitude`をMap上の写真撮影地点として再利用できる。ただしEXIF位置は写真に埋め込まれたsnapshotであり、連続的なLocationPointやPlaceVisitの代替にしない。

Phase 4完了時に次を引き渡す。

- 複数data typeが混在するTimeline discriminated unionと安定merge sort。
- point eventをtimezone日範囲で取得するquery / API pattern。
- nullableな緯度・経度のvalidationとprivacy上の扱い。
- data type別のunique worker、constraint、lease、retry、safe diagnostics。
- binary + metadataのsize制限、hash、atomic filesystem / SQLite保存方式。
- Android volume / permission変化をcursor後退やPC deleteへ変換しない方針。
- 合成privacy fixture、実機長時間試験、障害復旧記録の形式。

Phase 5ではLocation用work name、取得周期、foreground / background permission、battery budget、batch size、PlaceVisit生成を別途決定し、photo workerや`ACCESS_MEDIA_LOCATION`へ同居させない。
