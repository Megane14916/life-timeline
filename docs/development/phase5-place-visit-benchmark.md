# Phase 5 PlaceVisit rebuild benchmark

`stay_point_v1`の全device rebuildを、synthetic LocationPoint 105,000件（5分間隔・一定座標・accuracy 25m）で計測した。pointのDB投入時間は含めず、SQLiteから全pointを読み込み、並べ替え・stay point判定・PlaceVisitの置換までを計測している。

| 実行日 | OS | Python | SQLite | 入力point | 生成visit | rebuild時間 |
| --- | --- | --- | --- | ---: | ---: | ---: |
| 2026-09-14 | Windows | 3.13.15 | 3.53.1 | 105,000 | 1 | 3,941.6 ms |

再実行コマンド（`backend/`から）：

```powershell
uv run python scripts/benchmark_place_visits.py
```

計測は合成データだけを使用し、位置情報をログや出力へ記録しない。この結果では1年相当のpointを全件再計算できており、初版では先行cacheやincremental rebuildを導入しない。
