"""The only production factory for cloud models. Never constructs fake models."""

from langchain.chat_models import init_chat_model
from langchain_openai import OpenAIEmbeddings


# 用途：创建使用兼容协议的 LangChain 聊天客户端。
# 输入：settings：模型名、Key、服务地址等配置。
# 输出：聊天模型客户端；缺少必填配置时为 None。
def create_chat(settings):
    # LangChain 统一聊天模型的调用接口。openai 表示兼容协议，实际服务由 base_url 决定，
    # 因此也可以连接百炼的 OpenAI 兼容端点；返回 None 时由 graph.py 明确展示仅检索模式。
    if not settings.chat_configured:
        return None
    return init_chat_model(
        model_provider="openai",
        api_key=settings.agent_llm_api_key,
        base_url=settings.agent_llm_base_url,
        model=settings.agent_llm_model,
        temperature=0,
        timeout=45,
        max_retries=1,
    )


# 用途：创建与数据库向量维度匹配的 Embedding 客户端。
# 输入：settings：向量模型配置，可共用聊天 Key 和地址。
# 输出：向量客户端或 None；维度不匹配时抛 ValueError。
def create_embeddings(settings):
    # Embedding 把文本变成用于相似度搜索的向量，与生成回答的聊天模型是两项独立能力。
    # 维度必须与 PostgreSQL 的 vector(1024) 列一致，不能只修改配置中的数字。
    if settings.agent_embedding_dimensions != 1024:
        raise ValueError("向量维度必须为1024")
    if not settings.embedding_configured:
        return None
    return OpenAIEmbeddings(
        api_key=settings.agent_embedding_api_key or settings.agent_llm_api_key,
        base_url=settings.agent_embedding_base_url or settings.agent_llm_base_url,
        model=settings.agent_embedding_model,
        dimensions=1024,
        request_timeout=45,
        max_retries=1,
        check_embedding_ctx_length=False,
        chunk_size=16,
    )
