import re


def select_sources(candidates, allowed_ids, active_versions):
    allowed = set(map(str, allowed_ids))
    return [
        s
        for s in candidates
        if s["documentId"] in allowed and active_versions.get(s["documentId"]) == s["version"]
    ][:6]


def validate_citations(answer, sources):
    refs = [int(x) for x in re.findall(r"\[(\d+)\]", answer)]
    return bool(refs) and all(1 <= n <= len(sources) for n in refs)


def terms(question):
    latin = re.findall(r"[a-z0-9_]{2,}", question.lower())
    chinese = re.findall(r"[\u4e00-\u9fff]+", question)
    return list(
        dict.fromkeys(
            latin + [text[i : i + 2] for text in chinese for i in range(max(1, len(text) - 1))]
        )
    )[:32]


class Retriever:
    def __init__(self, repo, embedding):
        self.repo, self.embedding = repo, embedding

    def search(self, question, allowed_ids, department_id=None, role=None):
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
