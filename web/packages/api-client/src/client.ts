import { api } from './api';
import type { components } from './generated/secretpad';
import {
  Node,
  Project,
  DataTable,
  DataSource,
  JobExecution,
  User,
  CreateNodeInput,
  UpdateNodeInput,
  CreateDataSourceInput,
  CreateDataTableInput,
  DataTableColumn,
  BackendTableColumn,
} from './schemas';

type SecretPadResponse<T> = {
  status?: { code: number; msg?: string };
  data?: T;
};

function unwrap<T>(res: SecretPadResponse<T>): T {
  if (res.status && res.status.code !== 0) {
    throw new Error(res.status.msg || `API error ${res.status.code}`);
  }
  if (res.data === undefined || res.data === null) {
    throw new Error('API returned empty data');
  }
  return res.data;
}

function apiError(error: unknown): string {
  return String((error as any)?.message || error || 'Unknown error');
}

function mapBackendColumn(col: BackendTableColumn): DataTableColumn {
  return {
    name: col.colName || '',
    type: col.colType || '',
    comment: col.colComment,
    classification: 'L1',
  };
}

function mapJobStatus(status: string): JobExecution['status'] {
  switch (status) {
    case 'RUNNING':
      return 'RUNNING';
    case 'SUCCEED':
      return 'SUCCEEDED';
    case 'FAILED':
    case 'STOPPED':
      return 'FAILED';
    default:
      return 'PENDING';
  }
}

function formatDuration(start?: string, end?: string): string {
  if (!start) return '-';
  const startMs = new Date(start).getTime();
  const endMs = end ? new Date(end).getTime() : Date.now();
  if (!startMs || Number.isNaN(endMs)) return '-';
  const diff = Math.max(0, Math.floor((endMs - startMs) / 1000));
  const hours = Math.floor(diff / 3600);
  const mins = Math.floor((diff % 3600) / 60);
  const secs = diff % 60;
  if (hours > 0) return `${hours}h ${mins}m ${secs}s`;
  if (mins > 0) return `${mins}m ${secs}s`;
  return `${secs}s`;
}

export const apiClient = {
  async login(name: string, passwordHash: string): Promise<User> {
    const { data, error } = await api.POST('/api/login', {
      body: { name, passwordHash },
    });
    if (error || !data) {
      throw new Error(
        (error as any)?.message || (data as unknown as SecretPadResponse<unknown>)?.status?.msg || 'Login failed'
      );
    }
    const payload = unwrap(data as unknown as SecretPadResponse<User>);
    if (payload.token) {
      localStorage.setItem('secretpad-token', payload.token);
    }
    return payload;
  },

  async logout(): Promise<void> {
    await api.POST('/api/logout').catch(() => undefined);
    localStorage.removeItem('secretpad-token');
  },

  async getNodes(): Promise<Node[]> {
    const { data, error } = await api.POST('/api/v1alpha1/node/list', { body: {} as any });
    if (error) throw new Error(apiError(error));
    return unwrap(data as unknown as SecretPadResponse<Node[]>).map((n) => ({
      ...n,
      name: n.nodeName,
      status: n.nodeStatus,
      createTime: n.gmtCreate,
    }));
  },

  async createNode(input: CreateNodeInput): Promise<string> {
    const { data, error } = await api.POST('/api/v1alpha1/node/create', {
      body: input as components['schemas']['CreateNodeRequest'],
    });
    if (error) throw new Error(apiError(error));
    return unwrap(data as unknown as SecretPadResponse<string>);
  },

  async updateNode(input: UpdateNodeInput): Promise<string> {
    const { data, error } = await api.POST('/api/v1alpha1/node/update', {
      body: input as components['schemas']['UpdateNodeRequest'],
    });
    if (error) throw new Error(apiError(error));
    return unwrap(data as unknown as SecretPadResponse<string>);
  },

  async deleteNode(nodeId: string): Promise<void> {
    const { data, error } = await api.POST('/api/v1alpha1/node/delete', {
      body: { nodeId } as components['schemas']['NodeIdRequest'],
    });
    if (error) throw new Error(apiError(error));
    unwrap(data as unknown as SecretPadResponse<unknown>);
  },

  async refreshNode(nodeId: string): Promise<Node> {
    const { data, error } = await api.POST('/api/v1alpha1/node/refresh', {
      body: { nodeId } as components['schemas']['NodeIdRequest'],
    });
    if (error) throw new Error(apiError(error));
    const node = unwrap(data as unknown as SecretPadResponse<Node>);
    return { ...node, name: node.nodeName, status: node.nodeStatus, createTime: node.gmtCreate };
  },

  async getNodeToken(nodeId: string): Promise<{ token?: string; tokenStatus?: string; lastTransitionTime?: string }> {
    const { data, error } = await api.POST('/api/v1alpha1/node/token', {
      body: { nodeId } as components['schemas']['NodeTokenRequest'],
    });
    if (error) throw new Error(apiError(error));
    return unwrap(data as unknown as SecretPadResponse<{ token?: string; tokenStatus?: string; lastTransitionTime?: string }>);
  },

  async getProjects(): Promise<Project[]> {
    const { data, error } = await api.POST('/api/v1alpha1/project/list', { body: {} as any });
    if (error) throw new Error(apiError(error));
    return unwrap(data as unknown as SecretPadResponse<Project[]>).map((p) => ({
      ...p,
      name: p.projectName,
      createTime: p.gmtCreate,
      jobCount: p.jobCount ?? 0,
    }));
  },

  async getProjectDetail(id: string): Promise<Project | undefined> {
    const projects = await this.getProjects();
    return projects.find((p) => p.projectId === id);
  },

  async createProject(data: Partial<Project>): Promise<Project> {
    const { data: res, error } = await api.POST('/api/v1alpha1/project/create', {
      body: {
        name: data.projectName || data.name || '',
        description: data.description || '',
        computeMode: data.computeMode || 'FL',
        teeNodeId: '',
      } as components['schemas']['CreateProjectRequest'],
    });
    if (error) throw new Error(apiError(error));
    const created = unwrap(res as unknown as SecretPadResponse<components['schemas']['CreateProjectVO']>);
    return {
      projectId: created.projectId || String(Date.now()),
      projectName: data.projectName || data.name || 'New Project',
      name: data.name || data.projectName || 'New Project',
      description: data.description || '',
      computeMode: data.computeMode || 'FL',
      nodes: data.nodes || [],
      status: 'ACTIVE',
      jobCount: 0,
      gmtCreate: new Date().toISOString(),
      createTime: new Date().toISOString(),
    } as Project;
  },

  async getDataSources(ownerId: string): Promise<DataSource[]> {
    const { data, error } = await api.POST('/api/v1alpha1/datasource/list', {
      body: { ownerId, page: 1, size: 1000 } as components['schemas']['DatasourceListRequest'],
    });
    if (error) throw new Error(apiError(error));
    const list = unwrap(
      data as unknown as SecretPadResponse<{ infos?: DataSource[]; list?: DataSource[] }>
    );
    return (list.infos || list.list || []).map((ds) => ({
      ...ds,
      nodeId: ds.nodes?.[0]?.nodeId || '',
      status: ds.status || 'Available',
    }));
  },

  async createDataSource(input: CreateDataSourceInput): Promise<DataSource> {
    const { data, error } = await api.POST('/api/v1alpha1/datasource/create', {
      body: {
        ownerId: input.ownerId,
        nodeIds: input.nodeIds,
        type: input.type,
        name: input.name,
        dataSourceInfo: input.info,
      } as components['schemas']['CreateDatasourceRequest'],
    });
    if (error) throw new Error(apiError(error));
    const created = unwrap(data as unknown as SecretPadResponse<components['schemas']['CreateDatasourceVO']>);
    return {
      datasourceId: created.datasourceId || '',
      name: input.name,
      type: input.type,
      nodes: input.nodeIds.map((id) => ({ nodeId: id })),
      status: 'Available',
      info: input.info,
    };
  },

  async deleteDataSource(ownerId: string, datasourceId: string, type: string): Promise<void> {
    const { data, error } = await api.POST('/api/v1alpha1/datasource/delete', {
      body: { ownerId, datasourceId, type } as components['schemas']['DeleteDatasourceRequest'],
    });
    if (error) throw new Error(apiError(error));
    unwrap(data as unknown as SecretPadResponse<unknown>);
  },

  async getDataTables(ownerId?: string): Promise<DataTable[]> {
    const { data, error } = await api.POST('/api/v1alpha1/datatable/list', {
      body: { pageSize: 1000, pageNumber: 1, ownerId } as components['schemas']['ListDatatableRequest'],
    });
    if (error) throw new Error(apiError(error));
    const payload = unwrap(
      data as unknown as SecretPadResponse<{ datatableNodeVOList?: DataTable[]; list?: DataTable[] }>
    );
    const raw = payload.datatableNodeVOList || payload.list || [];
    return raw.map((item) => {
      const vo = (item as any).datatableVO || (item as any).table || item;
      const backendColumns: BackendTableColumn[] = vo?.schema || vo?.columns || [];
      const columns: DataTableColumn[] = backendColumns.map((c) =>
        c.colName ? mapBackendColumn(c) : (c as unknown as DataTableColumn)
      );
      return {
        ...item,
        tableId: vo?.datatableId || (item as any).tableId || '',
        tableName: vo?.datatableName || (item as any).tableName || '',
        nodeId: item.nodeId || vo?.nodeId || '',
        nodeName: item.nodeName || vo?.nodeName || '',
        datasourceId: vo?.datasourceId || '',
        datasourceType: vo?.datasourceType || '',
        relativeUri: vo?.relativeUri || '',
        status: vo?.status || item.status || 'Available',
        columns,
        rowCount: vo?.rowCount || 0,
        createTime: vo?.gmtCreate || item.gmtCreate,
      } as DataTable;
    });
  },

  async createDataTable(input: CreateDataTableInput): Promise<DataTable> {
    const { data, error } = await api.POST('/api/v1alpha1/datatable/create', {
      body: {
        ownerId: input.ownerId,
        nodeIds: input.nodeIds,
        datatableName: input.datatableName,
        datasourceId: input.datasourceId,
        datasourceName: input.datasourceName,
        datasourceType: input.datasourceType,
        relativeUri: input.relativeUri,
        columns: input.columns,
        desc: input.desc || '',
      } as components['schemas']['CreateDatatableRequest'],
    });
    if (error) throw new Error(apiError(error));
    const created = unwrap(data as unknown as SecretPadResponse<components['schemas']['CreateDatatableVO']>);
    const first = created.dataTableNodeInfos?.[0];
    return {
      tableId: first?.domainDataId || String(Date.now()),
      tableName: input.datatableName,
      nodeId: first?.nodeId || input.ownerId,
      datasourceId: input.datasourceId,
      datasourceType: input.datasourceType,
      relativeUri: input.relativeUri,
      columns: input.columns,
      rowCount: 0,
      status: 'Available',
      gmtCreate: new Date().toISOString(),
      createTime: new Date().toISOString(),
    } as DataTable;
  },

  async deleteDataTable(input: { nodeId: string; datatableId: string; datasourceId?: string; datasourceType?: string; relativeUri?: string }): Promise<void> {
    const { data, error } = await api.POST('/api/v1alpha1/datatable/delete', {
      body: {
        nodeId: input.nodeId,
        datatableId: input.datatableId,
        datasourceId: input.datasourceId,
        datasourceType: input.datasourceType,
        relativeUri: input.relativeUri,
      } as components['schemas']['DeleteDatatableRequest'],
    });
    if (error) throw new Error(apiError(error));
    unwrap(data as unknown as SecretPadResponse<unknown>);
  },

  async getProjectJobs(projectId: string, pageSize = 10, pageNum = 1): Promise<JobExecution[]> {
    const { data, error } = await api.POST('/api/v1alpha1/project/job/list', {
      body: { projectId, pageSize, pageNum } as components['schemas']['ListProjectJobRequest'],
    });
    if (error) throw new Error(apiError(error));
    const payload = unwrap(
      data as unknown as SecretPadResponse<{ data?: any[]; pageSize?: number; pageTotal?: number }>
    );
    const raw = payload.data || [];
    return raw.map((j) => ({
      jobId: j.jobId || '',
      projectId,
      name: j.graph?.name || `Job ${j.jobId || ''}`,
      status: mapJobStatus(j.status),
      duration: formatDuration(j.gmtCreate, j.gmtFinished || (j.finished ? j.gmtModified : undefined)),
      createTime: j.gmtCreate,
      errMsg: j.errMsg,
      finished: j.finished,
    }));
  },

  async getJobs(limit = 10): Promise<JobExecution[]> {
    const projects = await this.getProjects();
    const jobs: JobExecution[] = [];
    for (const project of projects.slice(0, 5)) {
      try {
        const list = await this.getProjectJobs(project.projectId, 10, 1);
        jobs.push(...list);
      } catch {
        // ignore per-project failures
      }
    }
    return jobs
      .sort((a, b) => new Date(b.createTime || 0).getTime() - new Date(a.createTime || 0).getTime())
      .slice(0, limit);
  },
};
