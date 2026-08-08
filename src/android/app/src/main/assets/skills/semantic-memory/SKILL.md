---
name: semantic-memory
description: HF 语义记忆系统 — 用自然语言搜索历史经验（不依赖关键词）。基于 HF Dataset + embeddings，实现跨会话的「真正回忆」。跑通后请移步 Settings → Skills 查看。
version: 1.0.0
---

# Semantic Memory Skill

## 这是什么
RikkaMinis 应用的外部语义记忆系统。基于 HF Dataset（存储）+ HF Inference / embeddings（检索），实现「用自然语言搜记忆，不依赖关键词匹配」。

## 何时触发
- **新会话启动时**：自动运行 `python3 /var/minis/skills/semantic-memory/semantic_memory.py search "<当前任务关键词>"` 获取相关经验
- **做技术决策前**：查之前有没有类似问题、同类 bug、已踩过的坑
- **遇到 bug 时**：搜历史中是否有相同的根因
- **会话结束 / 发现重要经验时**：运行 `build` 将新经验向量化上传

## 触发条件关键词
记忆 语义搜索 HF 经验 之前做过 有没有类似的 历史 经验教训 会话 上下文

## 工具
脚本在 `/var/minis/skills/semantic-memory/semantic_memory.py`

### build — 重建索引
```bash
python3 /var/minis/skills/semantic-memory/semantic_memory.py build
```
从 /var/minis/memory/ 下所有 daily logs 提取经验 → HF Inference 向量化 → 上传 HF Dataset → 保存本地向量索引

### search — 语义搜索
```bash
python3 /var/minis/skills/semantic-memory/semantic_memory.py search "<自然语言查询>"
```
用自然语言描述你想找什么，返回语义最相关的 5 条经验（含相似度分数和来源文件）

### status — 查看状态
```bash
python3 /var/minis/skills/semantic-memory/semantic_memory.py status
```

## HF 资源
- Dataset: `HF_USER_NAME/rikkaminis-memory` (private)
- 嵌入模型: `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`
- 向量维度: 384

## 与本地 memory_get 的关系
- `memory_get`：关键词精确匹配，适合查具体术语（如 "GITHUB_TOKEN"、"build-apk.yml"）
- semantic search：语义模糊匹配，适合查「感觉」——"之前有没有类似的事"、"滚动跳相关的东西"
- **两者互补，不是替代**。先用 semantic search 找方向，再用 memory_get 精确定位

## Agent 使用约定
- 每个会话启动时：用当前任务的 2-3 个核心关键词做一次 semantic search
- 不要等用户要求才查——主动查，主动引用历史经验
- 搜索结果中相似度 > 0.35 的条目视为相关，应纳入上下文
- 发现重要新经验时：用 memory_write 写入 daily log（本地），然后在本会话结束时提醒用户运行 build
