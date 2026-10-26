# 已有区块的一次性野生植物补生成

适用：FarmersDelight `1.0.3-folia26.3.2`，搭配 CraftEngine `26.9.2-folia26.3.1`。
Folia 安装 CraftEngine 的 `craft-engine-paper-plugin` JAR。

## 为什么加载成功却找不到植物

`Loaded pack: farmersdelight` 表示配置包被读取，`Loaded placed features ... (10)` 表示所有包合计注册的生成规则数。
它们不能证明某个区块实际生成了植物。普通自然生成只在新区块进行，并有群系、地面和概率要求；不会重新装饰已有区块。
默认野葱、野胡萝卜的稀有度是每区块 `1/120`，野番茄和野土豆为 `1/100`；满足概率后还需要合适的落点。
所以在一个已有村庄或少数区块里找不到植物，并不等同于整个插件未启用。

可在对应群系的新区域寻找植物，手持调试棒或对准植物使用 `/ce debug target-block` 查看 Custom → State。
`/fd stats` 的 `0 drop rules` 是附属动态规则计数，不包含 CE 配置里的内置掉落。

## 开启旧区块补生成

先停服备份地图，再安装新版。启动一次后，在 `plugins/FarmersDelight/config.yml` 中修改已存在的这一段；不要重复添加同名 YAML 节点：

```yaml
retro-generation:
  enabled: true
  worlds: []
  chunks-per-tick: 1
  attempts-per-plant: 32
  max-plants-per-chunk: 3
  density-multiplier: 1.0
  seed: 0
  exclude-spawn-radius: 128
  excluded-areas: []
  extra-protection-plugins: []
```

执行 `/fd reload config`。`worlds: []` 只处理 `server.properties` 的 `level-name` 主世界；
多世界服需要填写实际世界名，例如 `worlds: [world, survival]`。只处理 NORMAL 环境。

服务会逐步检查目标世界中已经加载的旧区块，后续玩家经过并加载的旧区块也会检查。
不会强制加载整张地图，不会调用地形生成器。每 tick 最多派发 `chunks-per-tick` 个区块，Folia 上由各区块所属区域线程读写。

`density-multiplier: 1.0` 沿用服务端当前 CE 植物配置的稀有度，补生成较稀疏，不保证每区块都有植物。
提高该值会提高旧区块中命中植物的概率，仍受单区块上限及全部安全检查约束；不改变新区块的普通生成。
**先预览再开启；已处理区块不会因为后来提高密度而重新补种。**

## “只补一次”的含义

- 每个符合扫描条件的旧区块在第一次尝试前写入 `farmersdelight:retro_generation_v1` 区块 PDC 标记，正常保存和重启后保留。
- 即使这次因概率、群系或落点没有放下任何植物，也会记录已处理；不反复抽概率。
- 重启、重载、重新进入、切换 enabled、修改 seed/密度都不会清除此标记；采掉植物后不会自动补回来。
- 开启期间的新区块会标记为已处理，仍使用 CE 普通生成，不会额外补一轮。
- 未就绪的区块、保护查询失败、含容器的区块会跳过，不写完成标记；没有进行过补生成的区块之后仍可接受首次检查。
- 关闭 `enabled` 可停止后续检查。标记没有过期、强制重跑或自动删除机制。

## 安全边界

只补野卷心菜、野葱、野番茄、野胡萝卜、野土豆、野甜菜、沙地灌木这 7 种单格陆生植物。
水稻需要占用水体，这个功能不补水稻。

必须是原版 `AIR`，下方是规则允许的自然地面，并满足群系、露天和领地检查。
不会替换水、草、花、作物、耕地、道路、已有方块或 CE 自定义块；空气位置存在 CE 残留自定义数据时也会跳过。
候选周围半径 3 格出现非自然建筑材料或 CE 方块会被拒绝；区块中有原版或 CE 方块实体（包括容器）时跳过整个区块。
不会拆方块、执行地形/结构重新生成，也不触发普通邻居物理更新。放置前发送可取消的 `BlockFormEvent`，事件后重新检查。

**无法自动辨认没有领地、完全用泥土/原木等自然材料建造的人工花园。**
这种区域请通过 `excluded-areas` 排除；坐标为包含边界的 X/Z 矩形，覆盖所有高度：

```yaml
  excluded-areas:
    - world: world
      min-x: -1000
      max-x: -500
      min-z: 200
      max-z: 800
```

默认还排除出生点周围 X/Z 各 128 格的方形范围。`exclude-spawn-radius: 0` 关闭这项排除。
上述检查保证本功能只向空气添加植物，不能判定所有自然材料的摆放者；备份可用于撤销不满意的新增位置。

支持 Residence、GriefPrevention 和 WorldGuard 的领地存在查询，不使用玩家权限或 OP 绕过。
WorldGuard 任意适用区域或全局区域都会跳过。检测到其他 CE 保护提供者、常见领地插件，或
`extra-protection-plugins` 中列出的已启用插件时，会暂停整个补生成并报告插件名。
领地 API 异常同样暂停，修复后执行 `/fd reload config`。

补生成读取当前 `plugins/CraftEngine/resources/farmersdelight/configuration/wild_plants.yml` 和 `wild_roots.yml`。
只接受可安全识别的单格植物、直接群系名和地面条件；未知谓词、额外维度限制、多格或自定义副作用行为会被跳过。
这是对既有地图的保守补充，不会逐条重放任意用户自定义世界生成器。

## 检查命令

需要 `farmersdelight.admin` 权限（默认 OP）：

- `/fd retrogen status`：配置开关、目标世界、规则数、已确认的可用规则数、CE 就绪状态、排队区块、本次启动已处理和已放置数量、保护阻止原因。
- `/fd retrogen preview`：在游戏中只读预览当前区块，列出候选植物和坐标，以及拒绝统计。关闭功能时也可预览，不写标记或方块。

`usable=?` 表示尚未在 CE 就绪后检查规则；启用并处理区块或执行预览后会更新。
状态计数是本次启动的统计，永久标记保存在各区块内。

预览状态：`processed` 已处理过；`block_entities` 有容器等方块实体；`world` 世界不在列表；
`unavailable` 当前不可安全处理；`ready` 检查完成，候选数为 0 时请看筛选统计。

常见统计：`CHANCE` 概率未命中；`BIOME` 群系不符；`OCCUPIED` 目标不是空气；`SOIL` 地面不符；
`BUILDING` 周边建筑/自定义块或不露天；`PROTECTED` 位于领地；`EXCLUDED` 出生点或坐标排除；`CUSTOM_BLOCK` 已有自定义数据。

## 本版验证范围（2026-10-26）

- Paper 普通/调试构建、Folia 26.3 构建、完整 JUnit 测试及仓库 13 项检查均通过；Folia 共 1,014 项测试，无失败、错误或跳过。
- 独立 Folia `26.3-5-8cffa90`、Java 25 测试服中，32 个新区块通过正常装饰链路生成了 115 株野生植物。
  此项仅在测试目录将 CE 稀有度改成必命中，验证链路；正式资源文件与发行包保留原始稀有度。
- 保存后再启用补生成的 6 个旧区块中，逐格核对了受控地表区域的 16,900 个既有非空气方块状态，
  并检查原版箱子、CE 烹饪锅的物品数量与 PDC 元数据，以及已有 CE 作物 ID。
- 验证关闭状态的预览不写方块或标记；容器区块跳过；事件取消或修改输出后不放置；各次读取和写入在所属 Folia 区域执行。
- 采掉一株新增植物后执行配置重载、正常关服重启，剩余植物数量不变；无落点/事件拒绝的区块标记也保持，
  之后改成适合的群系仍不再补种。

这些测试覆盖受控区域和正常保存/重启，不代表每个服务器的所有自定义生成器、保护插件版本或断电故障都已经实测。
