# Folia 26.3 构建与发布

## 本次修复

- 调整 CraftEngine 依赖加载顺序，修复启用时的类加载失败。
- 在 CraftEngine 关闭存储前保存烹饪锅、砧板、煎锅与炉灶快照，保留待恢复的 NBT 与物品数据。
- 修复野生植物占满默认方块状态池导致无法注册或生成的问题。
- 自动迁移旧版默认野生植物配置；先备份、原子写入，保留注释、版本键与自定义设置。
- 已在独立 Folia 26.3 build 5 alpha、Java 25 环境验证正常关服重启、容器内容与作物生成。

使用 `farmersdelight-1.0.3-folia26.3.1.jar`，配套安装
[CraftEngine 26.9.2-folia26.3.1](https://github.com/SWUST-Minecraft-Club/craft-engine/releases/tag/v26.9.2-folia26.3.1)
中的 `craft-engine-paper-plugin` JAR。两边都需要更新。
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
所有检查通过后，推送 `v*` tag 会发布 GitHub Release，上传 Folia 插件、`SHA256SUMS.txt` 和本文。
tag 必须精确匹配 `build.gradle.kts` 中的版本：

```bash
git tag -a v1.0.3-folia26.3.1 -m "Fix Folia persistence and wild crop generation"
git push origin main
git push origin v1.0.3-folia26.3.1
```

发布使用仓库自带的 `GITHUB_TOKEN`，无需额外 token；只有发布 job 申请 `contents: write`。
重新运行相同 tag 的工作流会更新已有 Release 附件。下载后可用 `sha256sum --check SHA256SUMS.txt` 校验。
后续发布先更新源码版本并提交；更新 CraftEngine 依赖时，同时更新工作流中的 `CRAFTENGINE_REF` 与 `CRAFTENGINE_VERSION`。

官方说明：[Java 安装与缓存](https://github.com/actions/setup-java)、[GitHub CLI 发布命令](https://cli.github.com/manual/gh_release_create)。
