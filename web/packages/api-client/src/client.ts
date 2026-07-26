import { Project, Node, DataTable, DataSource, JobExecution } from './schemas';

// Mock DB Initial Data
const mockNodes: Node[] = [
  { nodeId: 'alice', name: 'Alice Node (Center)', type: 'CENTER', status: 'Ready', ip: '192.168.1.101', cpu: 16, memory: 32, createTime: '2026-05-12 10:00:00' },
  { nodeId: 'bob', name: 'Bob Node (Edge)', type: 'EDGE', status: 'Ready', ip: '192.168.1.102', cpu: 8, memory: 16, createTime: '2026-05-12 10:05:00' },
  { nodeId: 'carol', name: 'Carol Partner Node', type: 'AUTONOMY', status: 'Ready', ip: '192.168.1.103', cpu: 12, memory: 24, createTime: '2026-06-01 14:20:00' },
];

const mockProjects: Project[] = [
  { projectId: 'proj-001', name: 'Medical Federated Learning Project', description: 'Cross-hospital privacy-preserving diagnosis model training', computeMode: 'FL', nodes: ['alice', 'bob'], status: 'ACTIVE', jobCount: 12, createTime: '2026-06-10 11:30:00' },
  { projectId: 'proj-002', name: 'Financial Risk Control Joint PSI', description: 'Bank & Fintech anti-fraud multi-party computation', computeMode: 'MPC', nodes: ['alice', 'bob', 'carol'], status: 'ACTIVE', jobCount: 28, createTime: '2026-06-15 09:15:00' },
  { projectId: 'proj-003', name: 'TEE Enclave Genomic Analysis', description: 'Secure hardware-assisted genetic disease inference', computeMode: 'TEE', nodes: ['alice'], status: 'ACTIVE', jobCount: 5, createTime: '2026-07-01 16:40:00' },
];

const mockDataTables: DataTable[] = [
  { tableId: 'tbl-101', tableName: 'patient_clinical_records', nodeId: 'alice', datasourceId: 'ds-1', columns: [{ name: 'patient_id', type: 'STRING', classification: 'L1' }, { name: 'diagnosis_code', type: 'STRING', classification: 'L3' }, { name: 'dosage_mg', type: 'FLOAT', classification: 'L2' }], rowCount: 154000, status: 'Available', createTime: '2026-06-11 08:30:00' },
  { tableId: 'tbl-102', tableName: 'credit_card_features', nodeId: 'bob', datasourceId: 'ds-2', columns: [{ name: 'user_hash', type: 'STRING', classification: 'L1' }, { name: 'score', type: 'FLOAT', classification: 'L2' }], rowCount: 890000, status: 'Available', createTime: '2026-06-16 10:12:00' },
];

const mockDataSources: DataSource[] = [
  { datasourceId: 'ds-1', name: 'Hospital Internal MySQL', type: 'MYSQL', nodeId: 'alice', status: 'Available', createTime: '2026-05-10 12:00:00' },
  { datasourceId: 'ds-2', name: 'Fintech ODPS Warehouse', type: 'ODPS', nodeId: 'bob', status: 'Available', createTime: '2026-05-11 15:30:00' },
];

const mockJobs: JobExecution[] = [
  { jobId: 'job-901', projectId: 'proj-001', name: 'XGBoost Federated Training', status: 'RUNNING', duration: '4m 12s', createTime: '2026-07-26 17:00:00' },
  { jobId: 'job-902', projectId: 'proj-002', name: 'PSI Intersection Task', status: 'SUCCEEDED', duration: '12m 45s', createTime: '2026-07-26 16:20:00' },
  { jobId: 'job-903', projectId: 'proj-001', name: 'DP Noise Feature Encoding', status: 'SUCCEEDED', duration: '1m 30s', createTime: '2026-07-26 15:10:00' },
];

export const apiClient = {
  async getNodes(): Promise<Node[]> {
    return Promise.resolve([...mockNodes]);
  },

  async getProjects(): Promise<Project[]> {
    return Promise.resolve([...mockProjects]);
  },

  async getProjectDetail(id: string): Promise<Project | undefined> {
    return Promise.resolve(mockProjects.find(p => p.projectId === id));
  },

  async createProject(data: Partial<Project>): Promise<Project> {
    const newProj: Project = {
      projectId: `proj-${Date.now().toString().slice(-4)}`,
      name: data.name || 'New Project',
      description: data.description || '',
      computeMode: data.computeMode || 'MPC',
      nodes: data.nodes || ['alice', 'bob'],
      status: 'ACTIVE',
      jobCount: 0,
      createTime: new Date().toISOString().replace('T', ' ').slice(0, 19),
    };
    mockProjects.unshift(newProj);
    return Promise.resolve(newProj);
  },

  async getDataTables(): Promise<DataTable[]> {
    return Promise.resolve([...mockDataTables]);
  },

  async getDataSources(): Promise<DataSource[]> {
    return Promise.resolve([...mockDataSources]);
  },

  async getJobs(): Promise<JobExecution[]> {
    return Promise.resolve([...mockJobs]);
  }
};
