# Folia 26.3 构建与发布

## 1.0.3-folia26.3.5：玩家手册自动加入 HandbookCore

- 内置 16 篇农夫乐事玩家指南，检测到 HandbookCore 后通过公开 API 自动注册，正常更新插件并重启即可阅读。
- `/fd reload` 刷新贡献，核心服务恢复后自动重新连接；停用或关闭 `handbook.enabled` 时注销。
- HandbookCore 为可选依赖，未安装时不影响玩法。正文与独立 YAML 使用同一内容源，保留章节跳转、物品预览与玩家配方入口。
- 单独维护过同 ID YAML 的服务器可继续使用手动内容；切换方式见[手册说明](https://github.com/SWUST-Minecraft-Club/Farmersdelight-Plugin/blob/main/docs/handbook/README.md)。

## 1.0.3-folia26.3.4：沿用原生植物簇分布

- Folia 26.3 补生成使用 CE 已加载的原生稀有度、落点、高度图、次数和偏移步骤，保留默认 64 次的植物簇形状。
- 移除最多 3 株和株间距限制；同一轮新增植物允许相邻。旧的两项次数/上限配置由配置更新器备份后移除。
- 保留空气、地面、群系、建筑、领地及容器保护。只在当前区块写入，周边只读检查要求区块已加载且属于当前 Folia 区域。
- 沿用所有 `.2` / `.3` 完成标记；更新不会重跑已处理区块，采集后也不补回。
- 与普通生成保持相同概率时，使用 `density-multiplier: 1.0`；不会修改 CE 的新区块生成概率。

## 1.0.3-folia26.3.3：查询实际植物与生成诊断

- 新增 `/fd retrogen locate`：只读查找已加载区块的实际野生植物坐标，最多列出最近 10 个。
- 已处理区块的预览明确解释一次性标记，增加世界、群系、密度和累计筛选诊断。
- `placed=4` 表示已经放置 4 个方块；原概率较低时，少量生成不容易直接看到。配置建议见[补生成说明](RETRO_GENERATION.md)。
- 保留所有已有完成标记、生成概率和放置安全条件；更新不触发重复补种。

## 1.0.3-folia26.3.2：已有区块只补生成一次

新增可选的旧区块补生成，设置、检查命令和边界见 [旧区块补生成说明](RETRO_GENERATION.md)。
每个区块在尝试补生成前写入永久标记；重启、重载、修改概率或采掉植物后都不会再补种。
这次只需更新 FarmersDelight，CraftEngine 继续使用 `26.9.2-folia26.3.1` 的 Paper loader。

## 已包含的 Folia 修复

- 调整 CraftEngine 依赖加载顺序，修复启用时的类加载失败。
- 在 CraftEngine 关闭存储前保存烹饪锅、砧板、煎锅与炉灶快照，保留待恢复的 NBT 与物品数据。
- 修复野生植物占满默认方块状态池导致无法注册或生成的问题。
- 自动迁移旧版默认野生植物配置；先备份、原子写入，保留注释、版本键与自定义设置。
- 已在独立 Folia 26.3 build 5 alpha、Java 25 环境验证正常关服重启、容器内容与作物生成。

使用 `farmersdelight-1.0.3-folia26.3.5.jar`，配套安装
[CraftEngine 26.9.2-folia26.3.1](https://github.com/SWUST-Minecraft-Club/craft-engine/releases/tag/v26.9.2-folia26.3.1)
中的 `craft-engine-paper-plugin` JAR。尚未安装该 CE 修复版时，两边都需要更新。
上述验证针对正常关服；本次修复不能恢复更新前已经丢失的数据。

## 本地构建

常规 Paper 1.21.5 构建使用 JDK 21 与 Maven 中的 CraftEngine 依赖：

```bash
./gradlew clean build --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3G
python3 tools/check_test_results.py .
python3 tools/run_all_checks.py --quiet
```

API 文档测试需要检出 `IOVEYOUMC0/FarmersdelightPluginWiKi` 到 `wiki/`。
CI 固定文档提交，避免外部文档更新改变同一 tag 的构建结果。

Folia 26.3 发布构建使用 JDK 25，插件字节码仍以 Java 21 为目标：

1. 检出 `.github/workflows/build-release.yml` 的 `CRAFTENGINE_REF` 指定的 CraftEngine 提交。
2. 在 CraftEngine 中构建 `:bukkit:paper-loader:shadowJar :core:adventure:shadowJar`。
3. 回到本仓库执行（将路径替换为实际检出目录）：

```bash
./gradlew clean build -PdebugTools=false -PfoliaVersion=26.3.build.5-alpha \
  -PceJar=/path/to/craft-engine/target/craft-engine-paper-plugin-26.9.2-folia26.3.1.jar \
  -PceLibraries=/path/to/craft-engine/core/adventure/build/libs \
  --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3G
```

`ceLibraries` 使用源码构建出的 Adventure bundle，无需启动服务器或拷贝服务器的运行库。
最终服务器插件是 `build/libs/farmersdelight-<版本>.jar`；`farmersdelight-plugin-*.jar` 是供开发者编译的 API 产物。

## tag 自动发布

`.github/workflows/build-release.yml` 包含两种 debug-tools 配置的常规构建、Folia 26.3 构建、JUnit 测试与仓库契约检查。
所有检查通过后，推送 `v*` tag 会发布 GitHub Release，上传 Folia 插件、`SHA256SUMS.txt`、补生成说明和本文。
tag 必须精确匹配 `build.gradle.kts` 中的版本：

```bash
git tag -a v1.0.3-folia26.3.5 -m "Automatically register the FarmersDelight player handbook"
git push origin main
git push origin v1.0.3-folia26.3.5
```

发布使用仓库自带的 `GITHUB_TOKEN`，无需额外 token；只有发布 job 申请 `contents: write`。
重新运行相同 tag 的工作流会更新已有 Release 附件。下载后可用 `sha256sum --check SHA256SUMS.txt` 校验。
后续发布先更新源码版本并提交；更新 CraftEngine 依赖时，同时更新工作流中的 `CRAFTENGINE_REF` 与 `CRAFTENGINE_VERSION`。

官方说明：[Java 安装与缓存](https://github.com/actions/setup-java)、[GitHub CLI 发布命令](https://cli.github.com/manual/gh_release_create)。
