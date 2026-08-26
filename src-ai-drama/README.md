# AI短剧工厂

端侧 AI 短剧制作工具：小说/剧本 → 资产生成 → AI 分镜 → 渲染合成，全流程质量闸门管控。

- 项目地址：<https://github.com/owlco001/ai-drama-factory>
- 许可证：[MIT](LICENSE)

## 功能

- **项目管理**：多项目 / 多剧集（episodes），每集独立持有剧本、资产、分镜与渲染记录
- **AI 资产提取**：大模型从小说/剧本自动提取角色、场景、道具清单（规则兜底）
- **资产生成与编辑**：文生图 / 图生图参考图挂载；点击卡片即可编辑描述、更换参考图
- **角色 DNA 6 姿态资产包**：正面锚点 / 45° 侧脸 / 全身骑乘 / 三种情绪特写
- **质量闸门**（QualityEngine）：
  - G1 文件级硬校验（格式 / 尺寸 / 正方形约束）
  - G2 多模态质检打分，缺陷词直接拒
- **AI 编剧 + 导演**：剧本一键拆解为镜头表，逐镜生成运镜视觉指令
- **六铁律 + 忠实性闸门**：台词逐字校验、资产真实绑定、时间逆转词表拦截
- **时代红线**：内置西汉末年—新莽历史约束，禁词走 negative_prompt 通道，支持按剧集放行
- **渲染队列**：断点续传、预算熔断、防重复扣费（checkpoint 语义）

## 架构

```
app/           Android 壳（Compose UI + Room 持久化 + Foreground Service）
core-engine/   纯 Kotlin 引擎（Provider 适配 / 质量闸门 / AI 编排 / 渲染队列）
```

`core-engine` 不依赖 Android，可独立用于 JVM 服务端。

## 构建

```bash
./gradlew :app:assembleDebug
```

要求：JDK 17+，Android SDK 34。

## License

MIT — 见 [LICENSE](LICENSE)。
