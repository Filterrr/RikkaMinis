#!/usr/bin/env python3
"""
RikkaMinis 语义记忆引擎
基于 HF Dataset + HF Inference (embeddings) 实现语义搜索

用法:
  python3 semantic_memory.py build     # 从 daily logs 提取→向量化→上传
  python3 semantic_memory.py search "<query>"  # 语义搜索
  python3 semantic_memory.py status    # 显示状态
"""

import os, json, re, pickle, math, sys, argparse
from pathlib import Path
from huggingface_hub import HfApi, InferenceClient

# ─── 配置 ───
EMBED_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
MEMORY_DIR = Path("/var/minis/memory")
SKILL_DIR = Path(__file__).resolve().parent  # 脚本所在目录（skills/semantic-memory）
WORK_DIR = SKILL_DIR
INDEX_FILE = WORK_DIR / "vector_index.pkl"

api = HfApi()
whoami = api.whoami()
HF_USER = whoami["name"]
DATASET = f"{HF_USER}/rikkaminis-memory"

# ─── 工具函数 ───

def cosine(a, b):
    dot = sum(x*y for x,y in zip(a,b))
    na = math.sqrt(sum(x*x for x in a))
    nb = math.sqrt(sum(y*y for y in b))
    return dot/(na*nb) if na*nb else 0


def extract_entries(glob_pattern="2026-*.md"):
    """从 daily logs 提取经验条目（按 ## 标题分割）"""
    skip_patterns = [
        "These are memories saved by you",
        "These are memories",
        "The following are memories",
        "auto-injected from daily",
        "These are",
    ]
    entries = []
    for md_file in sorted(MEMORY_DIR.glob(glob_pattern), reverse=True):
        text = md_file.read_text()
        sections = re.split(r'\n(?=## )', text)
        for sec in sections:
            sec = sec.strip()
            if not sec or len(sec) < 50:
                continue
            if any(sec.startswith(p) or p in sec[:200] for p in skip_patterns):
                continue
            title_match = re.match(r'^##\s+(.+)', sec)
            title = title_match.group(1).strip() if title_match else sec[:80]
            entries.append({
                "title": title,
                "content": sec,
                "source": str(md_file.name),
                "chars": len(sec),
            })
    return entries


def embed_texts(entries, client=None):
    """对条目列表做向量化，原地添加 embedding 字段"""
    if client is None:
        client = InferenceClient()
    total = len(entries)
    for i, e in enumerate(entries):
        text = (e["title"] + "\n" + e["content"])[:512]
        try:
            vec = client.feature_extraction(text=text, model=EMBED_MODEL)
            if hasattr(vec, "tolist"):
                vec = vec.tolist()
            e["embedding"] = vec
        except Exception as ex:
            print(f"  ⚠️ 向量化失败 [{i+1}/{total}]: {ex}")
            e["embedding"] = None
        if (i+1) % 20 == 0:
            print(f"  向量化进度: {i+1}/{total}")
    # 过滤失败条目
    return [e for e in entries if e.get("embedding") is not None]


def upload_to_hf(entries):
    """上传经验到 HF Dataset"""
    api.create_repo(repo_id=DATASET, repo_type="dataset", private=True, exist_ok=True)
    import tempfile
    tmp = Path(tempfile.mkdtemp()) / "memory.jsonl"
    with open(tmp, "w") as f:
        for e in entries:
            rec = {k: v for k, v in e.items() if k != "embedding"}
            rec["embed_sig"] = [round(e["embedding"][i], 4) for i in range(min(8, len(e["embedding"])))]
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    api.upload_file(
        path_or_fileobj=str(tmp),
        path_in_repo="memory.jsonl",
        repo_id=DATASET,
        repo_type="dataset",
    )
    return DATASET


def search(query, top_k=5, client=None):
    """语义搜索"""
    if not INDEX_FILE.exists():
        print("❌ 索引文件不存在，请先运行 build")
        return []
    if client is None:
        client = InferenceClient()
    idx = pickle.loads(INDEX_FILE.read_bytes())
    entries = idx["entries"]

    vec = client.feature_extraction(text=query[:512], model=EMBED_MODEL)
    if hasattr(vec, "tolist"):
        vec = vec.tolist()

    scores = [(cosine(vec, e["embedding"]), e) for e in entries]
    scores.sort(reverse=True)
    return scores[:top_k]


def print_search_results(results):
    """格式化打印搜索结果"""
    if not results:
        print("  无结果")
        return
    for rank, (score, e) in enumerate(results, 1):
        title = e["title"][:80]
        src = e.get("source", "?")
        print(f"  {rank}. [{src}] ({score:.3f}) {title}")


# ─── 子命令 ───

def cmd_build():
    print("📦 从 daily logs 提取经验...")
    entries = extract_entries()
    print(f"  提取 {len(entries)} 条")

    print("🧠 向量化（HF Inference）...")
    client = InferenceClient()
    entries = embed_texts(entries, client)
    print(f"  成功: {len(entries)} 条")

    print(f"☁️  上传到 HF Dataset: {DATASET}")
    upload_to_hf(entries)
    print(f"  ✅ 已上传")

    # 保存本地索引
    idx_data = {"entries": entries, "model": EMBED_MODEL, "count": len(entries)}
    INDEX_FILE.write_bytes(pickle.dumps(idx_data))
    print(f"  📍 本地索引: {INDEX_FILE} ({INDEX_FILE.stat().st_size} bytes)")
    print(f"\n📍 HF: https://huggingface.co/datasets/{DATASET}")


def cmd_search(query):
    print(f'🔍 语义搜索: "{query}"\n')
    results = search(query)
    print_search_results(results)


def cmd_status():
    if INDEX_FILE.exists():
        idx = pickle.loads(INDEX_FILE.read_bytes())
        print(f"索引条目: {idx['count']}")
        print(f"嵌入模型: {idx['model']}")
        print(f"索引文件: {INDEX_FILE} ({INDEX_FILE.stat().st_size} bytes)")
    else:
        print("❌ 索引不存在")
    print(f"HF Dataset: https://huggingface.co/datasets/{DATASET}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RikkaMinis 语义记忆引擎")
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("build", help="提取→向量化→上传")
    sub.add_parser("search").add_argument("query", help="搜索查询")
    sub.add_parser("status", help="显示状态")

    args = parser.parse_args()
    if args.cmd == "build":
        cmd_build()
    elif args.cmd == "search":
        cmd_search(args.query)
    elif args.cmd == "status":
        cmd_status()
    else:
        parser.print_help()
