
## v0.4 修复：剧本模式导入后资产生成入口缺失
- 根因：剧本模式仅写 stage_flags.script_mode=true 并跳过文本分析自动建卡，AssetsPage/AssetsLogic 无剧本→资产卡入口，资产列表恒为空，评审门无法放行，分镜渲染因缺资产ID被卡死。
- 修复：
  - AssetsLogic 新增 ScriptAssetExtractor 纯函数：解析「角色/人物/场景/道具：」清单行与场次标题行（第X场 / 场景N / SCENE N / 内景 / 外景 / INT. / EXT.），去重保序；isScriptMode(stage_flags) 宽松识别。
  - AssetsLogic.extractFromScript()（kind+prompt判重一键建卡）、pendingIdsOfKind(kind)。
  - AssetsViewModel：init 读 episodes.script_json + stage_flags；extractFromScript 提取→落库→逐卡触发生成；generatePendingOfKind。
  - AssetsPage：scriptMode=true 时显示「剧本模式 · 资产生成」卡片：「一键从剧本提取资产卡」+「逐类生成图像」按钮及结果提示。
- 测试：新增 ScriptAssetExtractionTest（6用例）。:app:assembleDebug 通过，全量30个单测全绿。
