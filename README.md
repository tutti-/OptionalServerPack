# OptionalServerPack

プレイヤーごとに「任意のリソースパックを適用するか」を選べるようにする Bukkit/Paper 系プラグインです。
他のプラグインが適用するパックには一切触りません（強制パックはそのプラグイン自身の適用に任せ、このプラグインは任意パック専用です）。

## 変更点

### 0.1.2
- メッセージの prefix（接頭辞）の設定をシンプルにしました：表示のオンオフは `messages.prefix-enabled`（true/false）、文言は `messages.prefix` です

### 0.1.1
- ポケボールの表示対策を内蔵：パックの適用・解除でリソースが再読み込みされる間、インベントリ内のポケボール（`pixelmon:poke_ball` とその蓋 `pixelmon:poke_ball_lid`）をダミー表示に差し替え、完了後に本物を再表示します（サーバー側の実物は変更しません）。このプラグインの切替だけでなく、ItemsAdder など他のプラグインのパック適用時にも働きます。config の `pokeball-fix: false` で無効化できます
  - ※ PokeBallPackFix（mod）側の対策は「パック削除」の検知が実サーバーで効かなかったため、このプラグイン側で確実に動く方式にしました。mod はそのままでも併用できます（二重に差し替えても実害はありません）
- メッセージの prefix（接頭辞）を config で変更可能にしました

### 0.1.0
- 初回リリース
- 任意のサーバーリソースパックをプレイヤーごとに ON/OFF 可能（デフォルトは OFF、参加時のプロンプトなし）
- ON にしたプレイヤーには参加時に自動で再送
- PlaceholderAPI 連携（`%optionalserverpack_status%` / `%optionalserverpack_on%`）
- ON/OFF 時のメッセージを config で変更可能
- 旧プラグイン ModelPackToggle からの設定・データの自動移行に対応


## 動作確認状況

- Paper 1.21.1 サーバーでの起動・パック配信（内蔵Webサーバー）・コマンド動作を確認済み
- 0.1.1 は Paper / Arclight 1.21.1（FeudalKings 1.0.1）の両方、0.1.2 は Arclight で起動確認済みです。ただしポケボール表示対策の実際の切り替え動作はクライアントが必要なため、実サーバーで `/serverpack off` 時にボールが崩れないか確認してください
- PlaceholderAPI 2.12.3 との同時起動と、プレースホルダーの登録（expansion: optionalserverpack）を確認済み。
  プレースホルダーの「値」そのものは実プレイヤーでしか確認できないため、ゲーム内で
  `/papi parse 自分の名前 %optionalserverpack_status%` を実行して確認してください
  （コンソールの papi parse は、参加したことのあるプレイヤーにしか使えません）

## 導入手順

1. `OptionalServerPack-0.1.2.jar` をサーバーの `plugins/` フォルダに入れて再起動（0.1.x 内の更新は jar の差し替えだけです）
   → `plugins/OptionalServerPack/`（config.yml と packs/ フォルダ）が生成されます
   ※ ModelPackToggle（1.x）からの移行：**旧 jar を削除して**新 jar を入れるだけです。
     旧データフォルダから config・プレイヤー設定・パックが自動で引き継がれます
   ※ 1.x 内での更新は jar の差し替えだけでOKでした（config はそのまま使えます。
     新しい設定項目はデフォルト値で動きます。項目を書きたい場合は config.yml を開いて追記してください）
2. 適用したいパックの zip を `plugins/OptionalServerPack/packs/` に置く
   （ファイル名は config.yml の `packs:` の file 名と一致させる。デフォルトではサンプル名が書いてあるので、実際のパック名に書き換えてください）
3. `plugins/OptionalServerPack/config.yml` を編集：
   - `public-host:` に、プレイヤーがサーバーへ接続するのと同じ公開アドレス（DDNS名やグローバルIP）
   - `web-port: 8173` をルーター／ファイアウォールで開放（プレイヤーがパックをダウンロードするために必要。ItemsAdder の自前ホスティングと同じ考え方）
4. 再起動するか、コンソールで `serverpack reload`

## プレイヤー側の動き

- **デフォルトはOFF**。参加しても何も送られず、確認メッセージも出ません
- 使いたいプレイヤーが自分で有効化します：
  - `/serverpack on` — 有効化（その場でパックが送られます。クライアントの「サーバーリソースパック」設定が「確認する」の場合、そのときだけ確認画面が出ます）
  - `/serverpack off` — 無効化（その場でパックを外します）
  - `/serverpack toggle` / `/serverpack status`
- ONにしたプレイヤーには、次回以降の参加時に自動で送り直します（確認画面は基本出ません）
- 選択は `plugins/OptionalServerPack/preferences.yml` に保存されます

## PlaceholderAPI 連携

PlaceholderAPI が入っているサーバーでは、次のプレースホルダーが使えます（スコアボードやタブリスト、メニュー表示用）。
入っていないサーバーでは連携部分だけ無効になり、プラグイン自体は通常通り動きます。

- `%optionalserverpack_status%` — そのプレイヤーの状態表示（デフォルト: `ON` / `OFF` / `未選択`）
- `%optionalserverpack_on%` — ONなら `true`、それ以外は `false`（条件分岐用）

表示文言は config.yml の `placeholder:` で変更できます（古い config のままでもデフォルト値で動きます）。

## メッセージ設定

`/serverpack on` / `off` を実行したときにプレイヤーへ表示されるメッセージは、config.yml の `messages:` で変更できます。

- `messages.on` — ONにしたとき
- `messages.off` — OFFにしたとき
- `messages.declined` — パックの確認画面で拒否されたとき
- `messages.prefix-enabled` — prefix（接頭辞）を表示するか（true/false、デフォルト: true）
- `messages.prefix` — prefix の文言（デフォルト: `&b[OptionalServerPack] &r`）

共通の仕様：

- **空文字にするとそのメッセージは表示されません**
- `&` の色コードが使えます（例: `&a有効化しました`）
- `%player%` がプレイヤー名に置き換わります
- 変更は `/serverpack reload` で反映されます（古い config のままでもデフォルト文言で動きます）

## 管理者向け

- `/serverpack reload` — **管理者専用**です（権限 `optionalserverpack.admin`、デフォルトはOPのみ。コンソールからは常に実行可）。
  config・パック・設定を再読み込みします（zip を差し替えたら実行。SHA-1 は自動で再計算されます）
- `/serverpack on <プレイヤー名>` など、他プレイヤーの指定も管理者専用です

## 注意・ヒント

- パックの zip 名はバージョン付き（v2 等）だと差し替えのたびに config の書き換えが必要です。
  `optional-pack.zip` のような固定名にリネームしておくと、差し替え＋ reload だけで済みます
- Pixelmon には「サーバー切り替え時にサーバーパックをクリアする」癖がありますが、
  このプラグインは参加のたびに保存済みの設定で送り直すので、単一サーバー運用では問題になりません
- パックを更新すると（中身が変わると）クライアントは新しいパックとして再ダウンロードします
- クライアントMOD「ResourcePackCached」（最新版含む）を入れているプレイヤーは、
  このプラグインのパックと ItemsAdder のパックが共存できません（MOD側が先のパックを落とします）。
  該当プレイヤーには MOD を外してもらってください
