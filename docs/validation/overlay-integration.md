# Overlay 导航显示验证

日期：2026-10-31（Asia/Taipei）。本记录仅适用于 fork 的 `feat/overlay-navigation` 分支，不代表上游 Alpha 已包含地图联动。Navigation 版本保持 `0.1.0-alpha.1`。

## 构建与自动回归

环境：Java 21.0.11、MC 1.21.1、NeoForge 21.1.249、Fabric Loader 0.16.14／Fabric API 0.116.17、MTR 4.1.0-beta.2。

执行：

```powershell
python tools/download_dependencies.py
.\gradlew.bat build testFabricCore
.\fabric\gradlew.bat -p fabric build
```

需先将 `JAVA_HOME` 指向本机 JDK 21。上述命令通过；两个 Core 测试任务各 **114 项：112 通过、2 跳过、0 失败**。跳过项为显式生成测试存档与旧版 Overlay 1.5.2 的可选只读测试。Fabric 构建本身没有另外配置一套客户端 JUnit；共享逻辑由 `testFabricCore` 使用 Fabric 的真实 MTR Core 包运行。

新增 **17 项** 回归：16 项 `OverlayNavigationTest` 与1项 `OverlayAbsenceTest`。区段解析和视图注册测试直接加载 GitHub v1.5.3 正式 NeoForge 附件内的 `SectionResolver` 和 `ViewRegistry`，通过公共 API 操作；时钟、网络或地图渲染不由夹具模拟为“游戏实测”。覆盖：

- 两次换乘区间、环线重复停靠、原生轨道分数、双方独立修订。
- 完成段35%、未完成段原样、未使用15%；返回上一步、全部完成、无步行伪轨道。
- 内容相同及仅上车状态变化时不重复解析；只关闭本方视图。
- 取消和断线后的迟到结果、进度更新竞态、新世界 epoch 重连、导航入口只注册一次。
- 多路径歧义、站台占位、完整停站序列不匹配、目录过期／不支持、切维度、身份缺失状态说明。
- 导航入口拒绝旧 epoch／未知车站；全网公共站台身份补齐；命名空间维度转换。
- 隔离类加载器不提供 Overlay API 或实现时，基础桥接仍加载并返回正常空结果。

开发 API 从固定 SHA256 的 Overlay 1.5.3 正式附件提取为 compileOnly JAR，未引用相邻工作区的未发布 API。MTR 两个依赖包由官方 Modrinth 元数据 SHA512 验证。产物 ZIP 检查通过：包含隔离适配器及中英状态文案、LICENSE／NOTICE，未包含 `io/github/teamcreating/mtrmap/` 或 `org/mtr/` 类。`git diff --check` 通过。

## 实机检查边界

在本仓库 `run/saves/NavTest` 新建独立平坦测试世界，并用原生 MTR Simulator 生成三站、三线路、车辆段及路径，生成任务通过。测试实例仅在本仓库 `run/mods` 安装了正式 Overlay 1.5.3、MTR、Xaero 与 JourneyMap，使用 `runClient -Pquickplay=NavTest` 启动。

日志确认 MTR 和地图资源加载以及 Overlay 协议8注册。Computer Use 首次列出测试 Minecraft 窗口，随后的 `get_window` 返回“window was not found”，刷新窗口列表后仍未出现该窗口。没有向不相关窗口发送输入。已停止本次测试启动的进程，未修改用户游戏实例。

因此 **尚未验收 Navigate 的实际点击、双地图可见高亮／淡化、完整世界内生命周期及 Fabric 游戏内显示**。没有用 API 测试替代这些项目，也没有本轮双地图截图。

## 待完成的手动验收

使用 MC 1.21.1、MTR 4.1.0-beta.2、对应加载器的本 fork 测试包、客户端和服务端 Overlay 1.5.3，并准备原生车辆段路径唯一的运营线路：

1. 分别在 Xaero 与 JourneyMap 点击车站卡片 Navigate，确认打开查询页、终点对应原生车站，活动行程不被替换。
2. 查询有班次的两段乘车方案，点击开始导航。确认仅精确乘车区间保持原色、其余线路／轨道淡化，列车和站点仍可正常使用。
3. 确认上车／下车、返回上一步、末段完成，逐项比较35%完成段与未完成段；环线重复经过的剩余区间应保持原色。
4. 关闭查询界面仍高亮；取消后正常地图或其他 provider 视图恢复。重连、切维度、修改线路目录后无旧高亮。
5. 测试人工步行换乘、同名不同ID站点、多条 Depot 路径歧义、缺少服务端 Overlay、仅 Navigation／仅 Overlay。歧义和缺失路径应显示状态，不能随机选路或残留上一行程。
6. 两个加载器分别验收；实际大网性能与多玩家联机还需独立测量。

本轮测试包不替换既有正式 release，也未修改上游 Overlay 或 Navigation。
