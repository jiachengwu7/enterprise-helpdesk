from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # Pydantic 将环境变量转换为有类型的配置；环境变量优先于 ai-service/.env。
    # .env.example 只是填写模板，不会被加载；修改配置后需要重启 AI 服务。
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

    # 用途：判断聊天模型必填配置是否存在，不请求云端。
    # 输入：实例中的 API Key 和模型名。
    # 输出：布尔值；不代表模型连通性。
    @property
    def chat_configured(self):
        # 这里只判断 Key 和模型名是否填写，不验证云端连通性、余额或模型权限。
        return bool(self.agent_llm_api_key and self.agent_llm_model)

    # 用途：判断向量模型是否具备调用配置。
    # 输入：独立或共用 Key、Embedding 模型名。
    # 输出：布尔值；不验证云端权限。
    @property
    def embedding_configured(self):
        return bool(
            (self.agent_embedding_api_key or self.agent_llm_api_key) and self.agent_embedding_model
        )
