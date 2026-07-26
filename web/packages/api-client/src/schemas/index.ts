import { z } from 'zod';

// User Schema (returned by /api/login)
export const UserSchema = z.object({
  ownerId: z.string(),
  name: z.string(),
  role: z.enum(['ADMIN', 'DEVELOPER', 'GUEST']).optional(),
  token: z.string(),
  platformType: z.string(),
  platformNodeId: z.string().optional(),
  ownerType: z.string().optional(),
  deployMode: z.string().optional(),
  apiResources: z.array(z.string()).optional(),
});
export type User = z.infer<typeof UserSchema>;

// Platform Schema
export const PlatformSchema = z.object({
  platformType: z.enum(['CENTER', 'EDGE', 'AUTONOMY', 'P2P', 'TEST']),
  nodeId: z.string().optional(),
});
export type Platform = z.infer<typeof PlatformSchema>;

// Node Schema (from /api/v1alpha1/node/*)
export const NodeSchema = z.object({
  nodeId: z.string(),
  nodeName: z.string(),
  controlNodeId: z.string().optional(),
  masterNodeId: z.string().optional(),
  type: z.string(),
  nodeStatus: z.string(),
  netAddress: z.string().optional(),
  description: z.string().optional(),
  gmtCreate: z.string(),
  mode: z.number().optional(),
  nodeAuthenticationCode: z.string().optional(),
  token: z.string().optional(),
  tokenStatus: z.string().optional(),
  // legacy optional fields kept for local mock fallbacks
  ip: z.string().optional(),
  cpu: z.number().optional(),
  memory: z.number().optional(),
  createTime: z.string().optional(),
  status: z.string().optional(),
  name: z.string().optional(),
});
export type Node = z.infer<typeof NodeSchema>;

// Project participant node
export const ProjectNodeSchema = z.object({
  nodeId: z.string(),
  nodeName: z.string().optional(),
  nodeType: z.string().optional(),
});
export type ProjectNode = z.infer<typeof ProjectNodeSchema>;

// Project Schema (from /api/v1alpha1/project/*)
export const ProjectSchema = z.object({
  projectId: z.string(),
  projectName: z.string(),
  description: z.string().optional(),
  computeMode: z.string(),
  nodes: z.array(ProjectNodeSchema).default([]),
  status: z.string(),
  jobCount: z.number().default(0),
  gmtCreate: z.string(),
  // legacy fallbacks
  name: z.string().optional(),
  createTime: z.string().optional(),
});
export type Project = z.infer<typeof ProjectSchema>;

// DataTable column (frontend normalized shape)
export const DataTableColumnSchema = z.object({
  name: z.string(),
  type: z.string(),
  comment: z.string().optional(),
  classification: z.string().optional(),
});
export type DataTableColumn = z.infer<typeof DataTableColumnSchema>;

// Raw backend table column shape
export const BackendTableColumnSchema = z.object({
  colName: z.string().optional(),
  colType: z.string().optional(),
  colComment: z.string().optional(),
});
export type BackendTableColumn = z.infer<typeof BackendTableColumnSchema>;

// DataTable Schema (from /api/v1alpha1/datatable/*)
export const DataTableSchema = z.object({
  tableName: z.string(),
  tableId: z.string(),
  nodeId: z.string(),
  nodeName: z.string().optional(),
  datasourceId: z.string().optional(),
  datasourceType: z.string().optional(),
  relativeUri: z.string().optional(),
  columns: z.array(DataTableColumnSchema).default([]),
  rowCount: z.number().default(0),
  status: z.string(),
  gmtCreate: z.string().optional(),
  description: z.string().optional(),
  // legacy fallbacks
  datatableVO: z.any().optional(),
  table: z.any().optional(),
  createTime: z.string().optional(),
});
export type DataTable = z.infer<typeof DataTableSchema>;

// DataSource related node
export const DataSourceNodeSchema = z.object({
  nodeId: z.string(),
  nodeName: z.string().optional(),
  status: z.string().optional(),
});
export type DataSourceNode = z.infer<typeof DataSourceNodeSchema>;

// DataSource Schema (from /api/v1alpha1/datasource/*)
export const DataSourceSchema = z.object({
  datasourceId: z.string(),
  name: z.string(),
  type: z.string(),
  nodes: z.array(DataSourceNodeSchema).default([]),
  status: z.string().optional(),
  createTime: z.string().optional(),
  info: z.record(z.any()).optional(),
  // legacy fallbacks
  nodeId: z.string().optional(),
});
export type DataSource = z.infer<typeof DataSourceSchema>;

// Job Execution Schema (normalized for UI)
export const JobExecutionSchema = z.object({
  jobId: z.string(),
  projectId: z.string(),
  name: z.string(),
  status: z.enum(['RUNNING', 'SUCCEEDED', 'FAILED', 'PENDING']),
  duration: z.string().optional(),
  createTime: z.string(),
  errMsg: z.string().optional(),
  finished: z.boolean().optional(),
});
export type JobExecution = z.infer<typeof JobExecutionSchema>;

// Form input schemas (runtime validation helpers)
export const CreateNodeInputSchema = z.object({
  name: z.string().min(1).max(32),
  mode: z.number().int().min(0).max(2),
});
export type CreateNodeInput = z.infer<typeof CreateNodeInputSchema>;

export const UpdateNodeInputSchema = z.object({
  nodeId: z.string(),
  netAddress: z.string().regex(/^.+:\d+$/),
});
export type UpdateNodeInput = z.infer<typeof UpdateNodeInputSchema>;

export const CreateDataSourceInputSchema = z.object({
  ownerId: z.string(),
  nodeIds: z.array(z.string()),
  type: z.string(),
  name: z.string().min(1).max(32),
  info: z.record(z.any()),
});
export type CreateDataSourceInput = z.infer<typeof CreateDataSourceInputSchema>;

export const CreateDataTableInputSchema = z.object({
  ownerId: z.string(),
  nodeIds: z.array(z.string()),
  datatableName: z.string().min(1).max(32),
  datasourceId: z.string(),
  datasourceName: z.string(),
  datasourceType: z.string(),
  relativeUri: z.string(),
  columns: z.array(DataTableColumnSchema),
  desc: z.string().max(100).optional(),
});
export type CreateDataTableInput = z.infer<typeof CreateDataTableInputSchema>;
