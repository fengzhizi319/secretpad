import { api } from './api';
import type { components } from './generated/secretpad';
import { Node, Project, DataTable, DataSource, JobExecution, User } from './schemas';

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
    if (error) throw new Error(String((error as any).message || error));
    return unwrap(data as unknown as SecretPadResponse<Node[]>).map((n) => ({
      ...n,
      name: n.nodeName,
      status: n.nodeStatus,
      createTime: n.gmtCreate,
    }));
  },

  async getProjects(): Promise<Project[]> {
    const { data, error } = await api.POST('/api/v1alpha1/project/list', { body: {} as any });
    if (error) throw new Error(String((error as any).message || error));
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
    if (error) throw new Error(String((error as any).message || error));
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
      body: { ownerId, page: 1, size: 1000 },
    });
    if (error) throw new Error(String((error as any).message || error));
    const list = unwrap(
      data as unknown as SecretPadResponse<{ infos?: DataSource[]; list?: DataSource[] }>
    );
    return (list.infos || list.list || []).map((ds) => ({
      ...ds,
      nodeId: ds.nodes?.[0]?.nodeId || '',
      status: ds.status || 'Available',
    }));
  },

  async getDataTables(ownerId?: string): Promise<DataTable[]> {
    const { data, error } = await api.POST('/api/v1alpha1/datatable/list', {
      body: { pageSize: 1000, pageNumber: 1, ownerId },
    });
    if (error) throw new Error(String((error as any).message || error));
    const payload = unwrap(
      data as unknown as SecretPadResponse<{ datatableNodeVOList?: DataTable[]; list?: DataTable[] }>
    );
    const raw = payload.datatableNodeVOList || payload.list || [];
    return raw.map((item) => {
      const vo = (item as any).datatableVO || (item as any).table || item;
      return {
        ...item,
        tableId: vo?.datatableId || (item as any).tableId || '',
        tableName: vo?.datatableName || (item as any).tableName || '',
        nodeId: item.nodeId || vo?.nodeId || '',
        nodeName: item.nodeName || vo?.nodeName || '',
        status: vo?.status || item.status || 'Available',
        columns: vo?.schema?.columns || vo?.columns || [],
        rowCount: vo?.rowCount || 0,
        createTime: vo?.gmtCreate || item.gmtCreate,
      } as DataTable;
    });
  },

  // Jobs remain mocked until a real P0 job list endpoint is wired.
  async getJobs(): Promise<JobExecution[]> {
    return [];
  },
};
