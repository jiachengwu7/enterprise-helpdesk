import re


# 用途：过滤未授权或过期来源并限制上下文数量。
# 输入：candidates：排序候选；allowed_ids：授权 ID；active_versions：当前版本映射。
# 输出：最多 6 个有效来源，保留候选顺序。
def select_sources(candidates, allowed_ids, active_versions):
    # 结果返回前再次核对授权集合和版本，最终最多给模型 6 个片段，控制上下文规模。
    allowed = set(map(str, allowed_ids))
    return [
        s
        for s in candidates
        if s["documentId"] in allowed and active_versions.get(s["documentId"]) == s["version"]
    ][:6]


# 用途：检查回答存在引用且编号落在候选范围内。
# 输入：answer：回答文本；sources：候选片段列表。
# 输出：布尔值；不证明引用在语义上支持结论。
def validate_citations(answer, sources):
    # 仅验证至少存在引用且编号未越界；不能据此断言每句话都被原文支持。
    refs = [int(x) for x in re.findall(r"\[(\d+)\]", answer)]
    return bool(refs) and all(1 <= n <= len(sources) for n in refs)


# 用途：提取英文单词和中文相邻双字检索词。
# 输入：question：用户问题文本。
# 输出：去重后最多 32 个检索词。
def terms(question):
    # 英文按词提取，中文用相邻两个字构造检索词；这是轻量方案，不是完整中文分词器。
    latin = re.findall(r"[a-z0-9_]{2,}", question.lower())
    chinese = re.findall(r"[\u4e00-\u9fff]+", question)
    return list(
        dict.fromkeys(
            latin + [text[i : i + 2] for text in chinese for i in range(max(1, len(text) - 1))]
        )
    )[:32]


class Retriever:
    # 用途：装配检索器使用的数据库和向量模型。
    # 输入：repo：检索仓储；embedding：可空向量客户端。
    # 输出：无返回值；保存依赖引用。
    def __init__(self, repo, embedding):
        self.repo, self.embedding = repo, embedding

    # 用途：执行授权范围内的混合检索，向量失败时保留关键词检索。
    # 输入：question、allowed_ids：问题及授权集合；department_id、role：身份范围。
    # 输出：经过版本与权限过滤的来源列表。
    def search(self, question, allowed_ids, department_id=None, role=None):
        # embed_query 将问题向量化；失败时仍可用关键词检索，聊天模型是否调用另行判断。
        if not allowed_ids:
            return []
        vector = None
        if self.embedding:
            try:
                vector = self.embedding.embed_query(question)
                if len(vector) != 1024:
                    vector = None
            except Exception:
                # Keyword retrieval remains available; generation mode is independent.
                vector = None
        candidates, versions = self.repo.search(
            allowed_ids, terms(question), vector, department_id=department_id, role=role
        )
        return select_sources(candidates, allowed_ids, versions)
