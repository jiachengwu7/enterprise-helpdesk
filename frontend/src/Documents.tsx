import { useEffect, useState, type FormEvent } from "react";
import {
  Download,
  FileText,
  Plus,
  Search,
  Trash2,
  Upload,
  RefreshCw,
  Lock,
  Globe,
} from "lucide-react";
import { api, downloadDocument, errorMessage } from "./api";
import type { Department, Document, User } from "./types";
import { canManageDocument } from "./permissions";
import {
  Badge,
  Empty,
  ErrorBox,
  Field,
  Loading,
  Modal,
  PageTitle,
  Refresh,
  formatDate,
  useLoad,
} from "./ui";
/**
 * 用途：展示知识库并提供筛选、上传和删除入口。
 * 输入：user：当前用户；departments：部门选项。
 * 输出：知识库页面 JSX；有处理中任务时轮询索引状态。
 */
export default function Documents({
  user,
  departments,
}: {
  user: User;
  departments: Department[];
}) {
  const list = useLoad<Document[]>(() => api("/documents"));
  const [search, setSearch] = useState(""),
    [filter, setFilter] = useState("ALL"),
    [editing, setEditing] = useState<Document | null | undefined>(undefined),
    [deleting, setDeleting] = useState<Document | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    // 上传接收成功不代表索引完成：只在有排队/处理中任务时轮询，离开页面时清理定时器。
    if (
      !list.data?.some(
        (d) => d.status === "QUEUED" || d.status === "PROCESSING",
      )
    )
      return;
    const timer = setInterval(() => void list.reload(), 4000);
    return () => clearInterval(timer);
  }, [list.data]);
  /**
   * 用途：下载所选文档并显示失败提示。
   * 输入：d：所选文档元数据。
   * 输出：Promise<void>；触发原件下载。
   */
  const download = async (d: Document) => {
    setError("");
    try {
      await downloadDocument(d.id, d.filename);
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  /**
   * 用途：删除选中的文档并刷新列表。
   * 输入：无参数，读取 deleting 状态。
   * 输出：Promise<void>；调用删除接口并更新界面。
   */
  const remove = async () => {
    if (!deleting) return;
    setBusy(true);
    setError("");
    try {
      await api(`/documents/${deleting.id}`, { method: "DELETE" });
      setDeleting(null);
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  const docs =
    list.data?.filter(
      (d) =>
        (filter === "ALL" || d.status === filter) &&
        `${d.title} ${d.filename}`.toLowerCase().includes(search.toLowerCase()),
    ) || [];
  return (
    <>
      <PageTitle
        eyebrow="KNOWLEDGE LIBRARY"
        title="企业知识库"
        description="汇集团队知识，保留每一个可信来源。"
      >
        <Refresh onClick={list.reload} busy={list.loading} />
        {user.role !== "EMPLOYEE" && (
          <button className="button primary" onClick={() => setEditing(null)}>
            <Plus size={17} />
            上传文档
          </button>
        )}
      </PageTitle>
      <div className="notice">
        <Lock size={16} />
        <span>
          仅展示公开及获授权的部门文档。更新文档后，系统会重新索引当前版本。
        </span>
      </div>
      <div className="panel">
        <div className="toolbar">
          <div className="search-field">
            <Search size={17} />
            <input
              aria-label="搜索文档"
              placeholder="搜索文档标题或文件名"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <select
            aria-label="索引状态筛选"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          >
            <option value="ALL">全部索引状态</option>
            <option value="READY">已就绪</option>
            <option value="QUEUED">排队中</option>
            <option value="PROCESSING">索引中</option>
            <option value="FAILED">索引失败</option>
          </select>
          <span className="muted">共 {docs.length} 份文档</span>
        </div>
        <ErrorBox
          message={list.error || error}
          retry={list.error ? list.reload : undefined}
        />
        {list.loading && !list.data ? (
          <Loading />
        ) : docs.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>文档名称</th>
                  <th>可见范围</th>
                  <th>索引状态</th>
                  <th>版本 / 分段</th>
                  <th>上传时间</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {docs.map((d) => (
                  <tr key={d.id}>
                    <td>
                      <div className="document-title">
                        <span className="file-icon">
                          <FileText size={22} />
                        </span>
                        <div>
                          <strong>{d.title}</strong>
                          <small>{d.filename}</small>
                          {d.error && (
                            <small className="error-text">{d.error}</small>
                          )}
                        </div>
                      </div>
                    </td>
                    <td>
                      <span className="scope">
                        {d.visibility === "PUBLIC" ? (
                          <Globe size={13} />
                        ) : (
                          <Lock size={13} />
                        )}
                        <Badge value={d.visibility} />
                      </span>
                      <small className="block muted">
                        {departments.find((x) => x.id === d.departmentId)
                          ?.name || d.departmentId}
                      </small>
                    </td>
                    <td>
                      <Badge value={d.status} />
                      {d.indexMode && (
                        <small className="block muted">{d.indexMode}</small>
                      )}
                    </td>
                    <td>
                      <strong>v{d.version}</strong>
                      <small className="block muted">
                        {d.chunkCount} 个分段
                      </small>
                    </td>
                    <td className="muted">{formatDate(d.createdAt)}</td>
                    <td>
                      <div className="row-actions">
                        <button
                          className="icon-button"
                          aria-label={`下载 ${d.title}`}
                          title="下载原件"
                          onClick={() => void download(d)}
                        >
                          <Download size={17} />
                        </button>
                        {canManageDocument(user, d) && (
                          <>
                            <button
                              className="icon-button"
                              aria-label={`替换 ${d.title}`}
                              title="更新版本"
                              onClick={() => setEditing(d)}
                            >
                              <RefreshCw size={16} />
                            </button>
                            <button
                              className="icon-button danger"
                              aria-label={`删除 ${d.title}`}
                              onClick={() => {
                                setError("");
                                setDeleting(d);
                              }}
                            >
                              <Trash2 size={16} />
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title={
              search || filter !== "ALL" ? "没有匹配的文档" : "知识库还没有文档"
            }
            description={
              user.role === "EMPLOYEE"
                ? "请联系管理员或部门处理员添加知识文档。"
                : "上传 TXT、Markdown、PDF 或 DOCX 文件，开始构建团队知识库。"
            }
          />
        )}
      </div>
      {editing !== undefined && (
        <DocumentForm
          user={user}
          departments={departments}
          doc={editing}
          onClose={() => setEditing(undefined)}
          onSaved={() => {
            setEditing(undefined);
            void list.reload();
          }}
        />
      )}
      {deleting && (
        <Modal title="删除知识文档" onClose={() => !busy && setDeleting(null)}>
          <p>
            确定删除「{deleting.title}
            」？原件和索引将移除，后续问答将不再检索此文档。
          </p>
          <ErrorBox message={error} />
          <div className="modal-footer">
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => setDeleting(null)}
            >
              取消
            </button>
            <button
              className="button danger-fill"
              disabled={busy}
              onClick={() => void remove()}
            >
              {busy ? "删除中…" : "确认删除"}
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
/**
 * 用途：渲染新建或版本更新表单。
 * 输入：user、departments：身份与选项；doc：可空旧文档；onClose、onSaved：回调。
 * 输出：文档表单 JSX。
 */
function DocumentForm({
  user,
  departments,
  doc,
  onClose,
  onSaved,
}: {
  user: User;
  departments: Department[];
  doc: Document | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [title, setTitle] = useState(doc?.title || ""),
    [department, setDepartment] = useState(
      doc?.departmentId || user.departmentId,
    ),
    [visibility, setVisibility] = useState(doc?.visibility || "DEPARTMENT"),
    [file, setFile] = useState<File | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  /**
   * 用途：以 multipart 表单提交文件及元数据。
   * 输入：e：表单事件；文件和字段来自组件状态。
   * 输出：Promise<void>；上传成功调用 onSaved，失败显示提示。
   */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!file) {
      setError("请选择文件。");
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setError("文件不能超过 10 MB。");
      return;
    }
    if (!/\.(txt|md|pdf|docx)$/i.test(file.name)) {
      setError("仅支持 TXT、MD、PDF 或 DOCX 文件。");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const form = new FormData();
      form.append("file", file);
      form.append("title", title.trim());
      form.append("departmentId", department);
      form.append("visibility", visibility);
      await api(doc ? `/documents/${doc.id}` : "/documents", {
        method: doc ? "PUT" : "POST",
        body: form,
      });
      onSaved();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={doc ? "更新文档版本" : "上传知识文档"}
      onClose={() => !busy && onClose()}
    >
      <form onSubmit={submit}>
        <Field label="文档标题">
          <input
            required
            maxLength={200}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="例如：员工设备申请指引"
          />
        </Field>
        <div className="form-grid">
          <Field label="所属部门">
            <select
              value={department}
              onChange={(e) => setDepartment(e.target.value)}
              disabled={user.role !== "ADMIN"}
            >
              {departments.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="可见范围">
            <select
              value={visibility}
              onChange={(e) =>
                setVisibility(e.target.value as typeof visibility)
              }
            >
              <option value="DEPARTMENT">仅本部门可见</option>
              <option value="PUBLIC">所有员工可见</option>
            </select>
          </Field>
        </div>
        <label className="upload-zone">
          <Upload size={27} />
          <strong>{file ? file.name : "选择要上传的文档"}</strong>
          <span>TXT / MD / PDF / DOCX · 最大 10 MB</span>
          <input
            aria-label="选择文档文件"
            type="file"
            accept=".txt,.md,.pdf,.docx"
            onChange={(e) => {
              const f = e.target.files?.[0] || null;
              setFile(f);
              if (f && !title) setTitle(f.name.replace(/\.[^.]+$/, ""));
            }}
          />
        </label>
        {doc && (
          <p className="muted">
            此操作将生成 v{doc.version + 1}，并重新建立索引。
          </p>
        )}
        <ErrorBox message={error} />
        <div className="modal-footer">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={onClose}
          >
            取消
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "上传中…" : doc ? "保存新版本" : "上传并索引"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
