# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 概要

Paperサーバー向けのカスタムMob導入プラグイン。`mobs.yml` の定義からMobを生成し、見た目を
リソースパックのカスタムモデルで差し替える。**Java版と統合版(Bedrock)の両方に配信している**のが
この構成の中心的な難しさで、それぞれ別の仕組みで描画される。

## ビルドとデプロイ

```bash
mvn -B package
```

Java 17 / paper-api 1.20.4。出力は `target/custom-mobs.jar`。**テストは存在しない**(`src/test` なし)ので、
検証は実サーバーへのデプロイで行う。

デプロイは手動コピーではなく、以下の経路で流れる:

1. `main` にpush → GitHub Actions(`.github/workflows/release.yml`)がビルドしてリリースを作成
2. MCサーバーのコンテナ再起動時に `plugins.txt` の `releases/latest/download/custom-mobs.jar` を取得

つまり**pushしてCIの完了を待ってからサーバーを再起動する**必要がある。CI完了は
`gh run list --branch main --limit 1` で確認する。

## アーキテクチャ

### 定義駆動 + 独自Tick

`mobs.yml` → `MobDefinitionLoader` → `MobDefinition`(`StatBlock` / `DropEntry` / `AiBehaviorConfig` /
`ModelConfig`)という流れで定義を読み、`MobManager.spawn()` が実体化する。

**バニラAIは `setAI(false)` で完全に切ってある。** すべての挙動は `CustomMobsPlugin` が1tickごとに回す
`MobManager.tickAll()` に移譲されている。この設計から来る重要な帰結:

- **移動は `setVelocity` ではなく `teleport` で行う**(AI無効下では速度が移動に反映されないため)。
  `MeleeAttackBehavior` がその実装例
- teleportは当たり判定を無視するので、`MeleeAttackBehavior` 側でブロックとの重なり
  (`Block#getBoundingBox`)を自前で見ている。壁は通れず、1ブロックまでの段差は登り、
  ハーフブロックやカーペットの高さにも合わせる。足場が見つからない場合はその高さのまま進む
  (**AI無効のMobは落下しない**ので、落とす処理は入れていない)
- モデル用ArmorStandの位置追従も毎Tickの `teleport` で行っている
- AIビヘイビアの例外は `MobManager.tickAll()` で握りつぶしてログに出す(同じ例外は初回のみ)。
  1体の例外で他のMobのTickを巻き込まないため

AIは `AiBehavior` インターフェースの実装を `MobManager.behaviorRegistry` に登録する形で拡張する。
YAMLの `type` 文字列がキーになる。

### 状態がメモリ上にしかない(拾い直しで補っている)

`activeMobs`(UUID → `CustomMobInstance`)はメモリ上のみにある。モデル用ArmorStandは
`setPersistent(false)` である一方、本体Mobの `setInvisible(true)` とPDCのmobIdタグは
NBTとして永続化される。

そのままだと**サーバー再起動やチャンクの読み直しでカスタムMobが「透明でAIも効かない置物」になる**ため、
`MobManager.adopt()` でPDCタグの付いたMobを拾い直している。呼び出し口は2つ:

- `MobEntityLoadListener`(`EntitiesLoadEvent`) — チャンクのエンティティが読み込まれたとき。
  1.17以降エンティティはチャンクとは別に読み込まれるので `ChunkLoadEvent` では拾えない。
  読み込みの最中に `spawnEntity` したくないので1tick遅らせて実行している
- `CustomMobsPlugin.onEnable()` の `adoptLoadedEntities()` — `/reload` や再有効化のとき

拾い直しでは**ステータスを再適用しない**(HPが全回復してしまうため)。定義がmobs.ymlから消えた
Mobは拾えないので、その場合は警告ログを出して `/cmob cleanup <mobId>` を案内する。

モデル用ArmorStandには持ち主のUUIDをPDC(`custom_mob_owner`)で持たせてあり、拾い直しのときに
既存のStandを再利用する(重複したStandはそこで消される)。`onDisable` でもStandだけは片付ける。

## 見た目の描画(最重要)

Java版と統合版で**まったく別の仕組み**が使われる。片方だけ直すとズレる。

| クライアント | 描画を決めるもの |
|---|---|
| Java版 | `test-resourcepack.zip` 内の `models/item/*.json` の `display.thirdperson_righthand` |
| 統合版 | `bedrock-pack/` のアタッチャブル + `animations/*.animation.json` の `rotation` |

**この2つの回転値は手動で揃える必要がある。** 詳細と実機で確認した制約一覧は
`bedrock-pack/README.md` にある。要点だけ挙げると:

- 統合版に橋渡しされるのは**ArmorStandの手持ちスロットだけ。** ItemDisplay・頭スロット・腕ポーズ・
  Javaモデルの `display` 変換はいずれも統合版では効かない
- 統合版で向きやサイズを制御する唯一の方法が**アタッチャブル**。Geyserはカスタムアイテムを
  `geyser_custom:<mapping-name>` で登録するので、その識別子に一致させる
- `.mcpack` は Geyser の `packs/` **直下**に置く(サブフォルダは読まれない)
- パックを変えたら `manifest.json` のバージョンを上げる(上げないとクライアントがキャッシュを使う)

`ModelConfig.scale` は読み込まれているが**どこからも使われていない**。スケールは現状
アタッチャブル側のアニメーションで決まる。

### Javaパック更新時の手順

`test-resourcepack.zip` はGitHub rawから配信されているため、変更したら:

1. zipを作り直してpush
2. `server.properties` の `resource-pack-sha1` を新しいsha1に更新
3. **GitHub rawのキャッシュ(`max-age=300`)が切れるまで待つ。** 古いzipが配信されている間は
   sha1不一致でクライアントがパックを拒否する。`curl -sI` の `source-age` で残り時間がわかる

## インフラと動作確認

Proxmox上のVMで動いており、構成は `Geyser(Velocity上) → Velocity → Paperサーバー`。

| ホスト | IP | 内容 |
|---|---|---|
| Paperサーバー | `10.2.1.30` | `/root/ms`(docker compose)。設定は `data/`、プラグイン設定は `data/plugins/CustomMobs/` |
| Velocity | `10.2.1.27` | `/root/velocity`(systemd `velocity.service`)。Geyserは `plugins/Geyser-Velocity/` |

SSH鍵は `C:\Users\swmr7\Documents\id_ed25519`(`root` で接続)。

ゲーム内の状態確認にはrconが使える。プレイヤーがいないとチャンクが読まれずエンティティを
取得できない点に注意:

```bash
docker exec paper-server rcon-cli 'execute positioned 0.0 0.0 0.0 run data get entity @e[type=armor_stand,sort=nearest,limit=1] equipment.head'
```

**Essentialsが `/kill` を上書きしている**ため、エンティティを消すときは `minecraft:kill` と明示する。
カスタムMobだけを消すなら `/cmob cleanup [mobId]` が使える(読み込み済みチャンクが対象。
本体MobとモデルStandの両方を消す)。

`/cmob spawn` は座標を渡せばrcon/コンソールからも実行できる:

```bash
docker exec paper-server rcon-cli 'cmob spawn miniyachiyo 0 64 0 world'
```

座標を省略した場合のみプレイヤー専用になる。`/cmob` には `custommobs.command` 権限(default: op)が要る。

## 描画の切り分け方

「見えない」の原因は複数ありうるので、順に潰すのが早い:

1. **サーバー側のデータを確認** — rconでArmorStandの存在・`equipment.head`(または `.mainhand`)・
   `Pos` を見る。`y-offset` の分だけ本体より上にいる点に注意
2. **バニラだけで対照実験** — 素のArmorStandに素のバニラアイテムを持たせて `summon` する。
   これが見えないならパックもプラグインも無関係
3. **どちらのクライアントか特定** — Floodgate経由の統合版プレイヤーは**ユーザー名の先頭にドット**が付く
   (例: `.kmgofficial`)。Java版か統合版かで原因が根本的に変わる
