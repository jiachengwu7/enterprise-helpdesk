from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=Path(__file__).resolve().parents[1] / ".env", extra="ignore", case_sensitive=False
    )
    ai_service_token: str = ""
    database_url: str = "postgresql://localhost:55432/helpdesk"
    redis_url: str = "redis://127.0.0.1:56379/0"
    s3_endpoint: str = "http://127.0.0.1:59000"
    s3_access_key: str = ""
    s3_secret_key: str = ""
    s3_bucket: str = "helpdesk"
    agent_llm_api_key: str = ""
    agent_llm_model: str = ""
    agent_llm_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    agent_embedding_model: str = "text-embedding-v3"
    agent_embedding_dimensions: int = 1024
    agent_embedding_api_key: str = ""
    agent_embedding_base_url: str = ""

    @property
    def chat_configured(self):
        return bool(self.agent_llm_api_key and self.agent_llm_model)

    @property
    def embedding_configured(self):
        return bool(
            (self.agent_embedding_api_key or self.agent_llm_api_key) and self.agent_embedding_model
        )
