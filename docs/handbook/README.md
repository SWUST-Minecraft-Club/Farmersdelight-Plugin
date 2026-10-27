# 农夫乐事玩家手册

内容包版本 **1.0.1**，面向玩家。FarmersDelight `1.0.3-folia26.3.5` 起内置并自动注册全部内容，支持 HandbookCore `Beta_0_1_0` / `Beta_0_1_1` 的公开 API。

## 阅读入口

在 `/handbook` 首页选择 **农夫乐事**，或直接打开：

```text
/handbook category farmersdelight:guide
/handbook open farmersdelight:welcome
```

16 篇文章覆盖介绍、第一天路线、野生食材、种植、番茄爬藤、水稻、小刀与稻草、砧板、炖锅、第一餐、炉灶与煎锅、堆肥与沃土、蘑菇群落、储物、食物效果和常见问题。
文章间有跳转，物品支持点击预览，配方按钮以玩家自己的权限执行 `/fd recipe`。
游戏内正文只讲玩法；配方和功能以所在服务器当前实际开放内容为准。

## 自动加入

服务器已安装 HandbookCore 时，更新 FarmersDelight JAR 并正常重启，手册首页会自动出现 **农夫乐事** 分类。
内容随插件 JAR 打包，更新插件会同步更新指南；`/fd reload` 会重新注册，反复重载不会增加重复条目。
`/handbook reload` 保留插件贡献。核心服务重新注册后会自动恢复指南；停用农夫乐事或关闭配置时会注销对应贡献。

可在 FarmersDelight 的 `config.yml` 中设置：

```yaml
handbook:
  enabled: true
```

未安装 HandbookCore 时，农夫乐事正常运行，手册联动等待核心可用。

## 单独维护 YAML

需要自行编辑指南时，可将 `handbook.enabled` 设为 `false`，并使用独立内容包。
若之前已经导入同 ID 的 YAML，保留它会继续使用手动维护的内容；自动注册遇到 ID 冲突时会保留现有手册并记录提示。
要改用自动内容，先备份并移出旧 YAML，执行 `/handbook reload` 后再执行 `/fd reload`。

将内容包解压到服务端根目录，新增文件为：

```text
plugins/HandbookCore/content/farmersdelight.yml
```

执行 `/handbook reload` 后即可阅读。已有同名文件时先备份，再合并或替换该文件。
此内容包使用独立的 `farmersdelight:` 手册 ID，不需要替换 HandbookCore 的 `content/server.yml`；也可与其中的农场入门示例共存。
独立内容包使用已有书页与物品资源，安装内容后只需重载手册。

## 内容维护与打包

唯一内容源是 [farmersdelight.yml](https://github.com/SWUST-Minecraft-Club/Farmersdelight-Plugin/blob/main/docs/handbook/content/farmersdelight.yml)。维护时，在 Farmersdelight-Plugin 源码仓库根目录执行：

```bash
python3 tools/package_handbook.py
```

默认在 `build/handbook/` 生成内容 ZIP、独立 YAML 和 `HANDBOOK-SHA256SUMS.txt`。
打包前会检查重复 ID、内部链接、玩家指令白名单、实际 CE 物品 ID 和 YAML 结构。

本次正文对照了本仓当前物品、作物行为、生成群系和配方；玩家参考文档来自同工作区的 FarmersdelightPluginWiKi。
水稻种植以本仓 `RiceCropRules` 的一格深静水规则为准；未将不同插件的季节、浇水或经营规则混入本章。

## 验证范围

自动联动已在真实 Folia 26.3 与 HandbookCore `Beta_0_1_0`、`Beta_0_1_1` 上各通过 109 项检查：无手动 YAML 的启动注册、与核心解析结果一致、重复重载、配置开关、核心服务注销与恢复、ID 冲突保留原内容、关闭后清理均通过。
移除 HandbookCore JAR 后，FarmersDelight 仍成功启动并重载，未产生缺失 API 的类加载错误。

玩家正文已在隔离 Folia 服务端使用真实 HandbookCore 加载器与分页器验证：16 篇文章、48 张纸页，全部向普通玩家开放；20 处章节跳转、6 处配方指令与 10 处物品预览入口保留。
检索、与默认示例共存、每页 8 行及 176 像素栏宽检查通过。CE 物品 ID 与当前插件配置一致。
这轮验证覆盖服务端加载和排版数据，未进行客户端截图或实际配方按钮点击验证。
