# 农夫乐事玩家手册

内容包版本 **1.0.0**，面向玩家，适用于当前 FarmersDelight `1.0.3-folia26.3.4` 的玩法内容和 HandbookCore `Beta_0_1_0` 的 YAML 格式。

## 阅读入口

在 `/handbook` 首页选择 **农夫乐事**，或直接打开：

```text
/handbook category farmersdelight:guide
/handbook open farmersdelight:welcome
```

16 篇文章覆盖介绍、第一天路线、野生食材、种植、番茄爬藤、水稻、小刀与稻草、砧板、炖锅、第一餐、炉灶与煎锅、堆肥与沃土、蘑菇群落、储物、食物效果和常见问题。
文章间有跳转，物品支持点击预览，配方按钮以玩家自己的权限执行 `/fd recipe`。
游戏内正文只讲玩法；配方和功能以所在服务器当前实际开放内容为准。

## 加入现有手册

将内容包解压到服务端根目录，新增文件为：

```text
plugins/HandbookCore/content/farmersdelight.yml
```

执行 `/handbook reload` 后即可阅读。已有同名文件时先备份，再合并或替换该文件。
此内容包使用独立的 `farmersdelight:` 手册 ID，不需要替换 HandbookCore 的 `content/server.yml`；也可与其中的农场入门示例共存。
它是手册内容扩展，无需替换 FarmersDelight JAR。使用已有书页与物品资源，安装内容后只需重载手册。

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

已在隔离 Folia 服务端使用真实 HandbookCore 加载器与分页器验证：16 篇文章、48 张纸页，全部向普通玩家开放；20 处章节跳转、6 处配方指令与 10 处物品预览入口保留。
检索、与默认示例共存、每页 8 行及 176 像素栏宽检查通过。CE 物品 ID 与当前插件配置一致。
这轮验证覆盖服务端加载和排版数据，未进行客户端截图或实际配方按钮点击验证。
