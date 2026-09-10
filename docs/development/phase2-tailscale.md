# Phase 2 Tailscale Serve接続手順

この文書は、FastAPIをLANやinternetへ直接公開せず、同じtailnetに参加したAndroidからTailscale Serve経由でSync APIへ接続するための手順です。

実tailnet名、PC名、ユーザー名、端末のidentity、URL、アクセストークンはrepositoryへ保存しません。以下の`<...>`は、実行時に自分の環境の値へ置き換えてください。

## 構成と安全条件

```text
Android
  → Tailscale tailnet / ACL
  → Tailscale Serve HTTPS
  → http://127.0.0.1:8000
  → FastAPI Sync API
```

必ず次を満たします。

- FastAPIは`127.0.0.1:8000`だけで待ち受ける。`0.0.0.0`、LANアドレス、internetへbindしない。
- AndroidにはServeが表示する`https://<machine>.<tailnet>.ts.net`を設定する。
- Tailscale tailnetのACLで、許可したAndroidまたは利用者から対象PCのTCP 443だけを許可する。
- `tailscale funnel`、自己署名証明書、cleartext HTTP、証明書検証の無効化を使わない。
- Tailscaleのidentity headerや利用アプリ一覧をログ・画面・repositoryへ記録しない。

Serveはtailnet内だけに公開する機能です。internet公開が必要な`Funnel`とは別の機能なので、コマンドを取り違えないでください。

## あなたが行う事前準備

1. WindowsへTailscaleをインストールし、PCでログインします。
2. AndroidへTailscaleアプリをインストールし、PCと同じtailnetへログインします。
3. tailnet管理画面でMagicDNSとHTTPS certificatesを有効にします。
4. ACLで、対象PCのHTTPSへ接続できるAndroidまたは利用者を最小限に許可します。

ACLはtailnetの既存方針に合わせて管理画面で設定してください。概念例は次のとおりです。`group:lifetimeline-android`と`tag:lifetimeline-pc`は実際に作成した主体・tagへ置き換え、広範な`*`や`0.0.0.0/0`を指定しないでください。

```json
{
  "acls": [
    {
      "action": "accept",
      "src": ["group:lifetimeline-android"],
      "dst": ["tag:lifetimeline-pc:443"]
    }
  ]
}
```

## Windows側の実行手順

### 1. 専用データディレクトリでBackendを起動

repository rootのPowerShellで実行します。

```powershell
cd backend
uv sync --all-groups --frozen
$env:LIFE_TIMELINE_DATA_DIR = Join-Path $env:TEMP 'life-timeline-phase2-tailscale'
New-Item -ItemType Directory -Force -Path $env:LIFE_TIMELINE_DATA_DIR | Out-Null
uv run alembic upgrade head
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

別のPowerShellで、まずloopbackのhealthを確認します。

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
Get-NetTCPConnection -State Listen -LocalPort 8000 |
  Select-Object LocalAddress, LocalPort, OwningProcess
```

期待値はhealthの`status`が`ok`で、`LocalAddress`が`127.0.0.1`だけであることです。`0.0.0.0`、`::`、PCのLANアドレスが表示された場合はBackendを停止し、起動引数を修正してください。

### 2. Serveを設定

管理者権限が必要なWindows環境では管理者PowerShellを使います。repository rootから次を実行します。

```powershell
.\scripts\tailscale-serve.ps1 -Action configure -LocalPort 8000
```

このスクリプトはBackendがloopback-onlyで待ち受けていることを確認してから、次の形式でbackground Serveを設定します。

```powershell
tailscale serve --bg 8000
tailscale serve status
```

表示されたHTTPS URLは、ローカルのメモへ一時的に控えます。repository、Issue、PR、ログへ実値を書きません。

設定だけ確認する場合は、状態をJSONで保存せず画面で確認します。

```powershell
tailscale serve status --json
```

Serveの設定には`http://127.0.0.1:8000`へのproxyがあり、公開側がHTTPSであることを確認します。

### 3. HTTPS healthを確認

PC自身から、次を実行します。

```powershell
.\scripts\tailscale-serve.ps1 `
  -Action check `
  -Endpoint 'https://<machine>.<tailnet>.ts.net'
```

次にAndroidのブラウザから同じhostnameの`/api/v1/health`を開き、`{"status":"ok"}`を確認します。Androidアプリでは、画面の`PC endpoint (HTTPS)`へ同じURLを入力して保存し、Usage Accessを許可した後に`収集して同期`を実行します。

## 障害と復旧の確認

各試験では、Android側でSessionを収集してから同期を実行し、失敗後も`Pending`件数が減らないことを確認します。復旧後に再度同期し、同じSession IDが二重登録されず、ACK済みだけが`Pending 0`になることを確認します。

| 試験 | 操作 | 期待結果 | 復旧 |
| --- | --- | --- | --- |
| FastAPI停止 | BackendのPowerShellで`Ctrl+C` | Androidは接続失敗、Pending維持 | Backendを同じ引数で再起動し、手動同期 |
| Serve停止 | `./scripts/tailscale-serve.ps1 -Action disable` | HTTPS到達失敗、Pending維持 | `-Action configure -LocalPort 8000`を再実行 |
| Tailscale停止 | TailscaleアプリでDisconnect、または`tailscale down` | tailnet到達失敗、Pending維持 | Tailscaleを再接続し、Serve statusを確認 |
| ACL拒否 | 一時的にAndroid主体の443許可を外す | HTTPS到達失敗、Pending維持 | ACLを元へ戻し、Androidのtailnet接続を確認 |
| PC sleep | PCをスリープさせてから同期 | 接続失敗またはtimeout、Pending維持 | PC復帰後、Backend/Serve statusを確認して再同期 |

障害中にAndroidアプリを再起動しても、device IDとPending件数が保持されることを確認します。payloadや個人のアプリ一覧は画面キャプチャ・ログ・PRへ載せません。

Serveを使い終わったら、共有を停止します。

```powershell
.\scripts\tailscale-serve.ps1 -Action disable
```

## 接続確認チェックリスト

以下を上から順に実施し、実値ではなく`PASS` / `FAIL`と安全な補足だけを記録してください。

- [ ] Backend healthが`http://127.0.0.1:8000/api/v1/health`で200を返した
- [ ] Backendのlisten addressが`127.0.0.1`だけだった
- [ ] `tailscale serve status`でHTTPS Serveとloopback proxyを確認した
- [ ] PCからHTTPS `/api/v1/health`が200を返した
- [ ] AndroidからHTTPS `/api/v1/health`が200を返した
- [ ] Androidの収集・同期成功後、PCのSync APIとTimelineで確認できた
- [ ] FastAPI / Serve / Tailscale / ACL / sleepの停止試験でPendingが維持された
- [ ] 復旧後の手動再送で同じSessionが二重登録されなかった
- [ ] Funnel、LAN直接公開、cleartext、TLS検証回避を使っていない
- [ ] hostname、tailnet名、identity、アプリ一覧、tokenをrepositoryへ残していない

### 2026-09-10 実機確認結果

利用者のWindows / Android環境で、次の結果を確認済みです。実際のhostname、tailnet名、identity、Session内容は記録していません。

| 確認項目 | 結果 | 確認内容 |
| --- | --- | --- |
| AndroidからHTTPS health | PASS | Android Chromeで`{"status":"ok"}`を確認 |
| Androidアプリの同期 | PASS | Androidアプリから同期を実行できた |
| FastAPI停止時の保持 | PASS | FastAPI停止後もPendingが維持された |
| FastAPI復旧後の再同期 | PASS | FastAPI再起動後に再同期できた |
| 再送の冪等性 | PASS | 復旧後の再同期で重複登録が発生しなかった |
| Tailscale切断・再接続 | PASS | Tailscaleのオン / オフ切替後も、再接続すれば通信が復旧した |

### このcheckoutでの準備状況

この開発環境ではTailscale CLIを実行していませんが、利用者環境ではTailscaleアプリを使った実機通信を確認済みです。Serveの設定変更・状態確認・障害切り分けをCLIで行う場合だけ、Tailscale CLIをPATHから利用できるようにしてください。

## 参照

- [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve)
- [tailscale serve command](https://tailscale.com/docs/reference/tailscale-cli/serve)
- [Install Tailscale on Windows](https://tailscale.com/docs/install/windows)
