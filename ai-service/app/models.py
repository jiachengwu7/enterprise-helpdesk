"""The only production factory for cloud models. Never constructs fake models."""

from langchain.chat_models import init_chat_model
from langchain_openai import OpenAIEmbeddings


def create_chat(settings):
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


def create_embeddings(settings):
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
