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


// Model / Serving Schema (from /api/v1alpha1/model/*)
export const ModelPackVOSchema = z.object({
  gmtCreate: z.string().optional(),
  modelDesc: z.string().optional(),
  modelId: z.string().optional(),
  modelName: z.string().optional(),
  modelStats: z.string().optional(),
  ownerId: z.string().optional(),
  servingId: z.string().optional(),
});
export type ModelPackVO = z.infer<typeof ModelPackVOSchema>;

export const ServingDetailSchema = z.object({
  endpoints: z.string().optional(),
  featureHttp: z.string().optional(),
  featureMappings: z.record(z.string()).optional(),
  isMock: z.boolean().optional(),
  nodeId: z.string().optional(),
  nodeName: z.string().optional(),
  sourcePath: z.string().optional(),
});
export type ServingDetail = z.infer<typeof ServingDetailSchema>;

export const ModelPackInfoVOSchema = z.object({
  modelStats: z.string().optional(),
  servingDetails: z.array(ServingDetailSchema).optional(),
});
export type ModelPackInfoVO = z.infer<typeof ModelPackInfoVOSchema>;

// Message Schema (from /api/v1alpha1/message/*)
export const MessageVOSchema = z.object({
  createTime: z.string().optional(),
  messageName: z.string().optional(),
  status: z.string().optional(),
  type: z.string().optional(),
  voteID: z.string().optional(),
});
export type MessageVO = z.infer<typeof MessageVOSchema>;

// Scheduled / Periodic Task Schema (from /api/v1alpha1/scheduled/*)
export const PageScheduledVOSchema = z.object({
  createTime: z.string().optional(),
  creator: z.string().optional(),
  owner: z.string().optional(),
  ownerName: z.string().optional(),
  scheduleDesc: z.string().optional(),
  scheduleId: z.string().optional(),
  scheduleStats: z.string().optional(),
  taskRunning: z.boolean().optional(),
});
export type PageScheduledVO = z.infer<typeof PageScheduledVOSchema>;

// Graph / DAG Schema (from /api/v1alpha1/graph/*)
export const GraphMetaVOSchema = z.object({
  graphId: z.string().optional(),
  name: z.string().optional(),
  ownerId: z.string().optional(),
  projectId: z.string().optional(),
});
export type GraphMetaVO = z.infer<typeof GraphMetaVOSchema>;

export const GraphNodeDetailSchema = z.object({
  graphNodeId: z.string().optional(),
  codeName: z.string().optional(),
  label: z.string().optional(),
  status: z.string().optional(),
  x: z.number().optional(),
  y: z.number().optional(),
  progress: z.number().optional(),
  inputs: z.array(z.string()).optional(),
  outputs: z.array(z.string()).optional(),
  nodeDef: z.record(z.any()).optional(),
  parties: z.array(z.record(z.any())).optional(),
  results: z.array(z.record(z.any())).optional(),
});
export type GraphNodeDetail = z.infer<typeof GraphNodeDetailSchema>;

export const GraphNodeInfoSchema = z.object({
  graphNodeId: z.string().optional(),
  codeName: z.string().optional(),
  label: z.string().optional(),
  x: z.number().optional(),
  y: z.number().optional(),
  inputs: z.array(z.string()).optional(),
  outputs: z.array(z.string()).optional(),
  nodeDef: z.record(z.any()).optional(),
});
export type GraphNodeInfo = z.infer<typeof GraphNodeInfoSchema>;

export const GraphEdgeSchema = z.object({
  edgeId: z.string().optional(),
  source: z.string().optional(),
  target: z.string().optional(),
  sourceAnchor: z.string().optional(),
  targetAnchor: z.string().optional(),
});
export type GraphEdge = z.infer<typeof GraphEdgeSchema>;

export const GraphDataSourceConfigSchema = z.object({
  nodeId: z.string().optional(),
  dataSourceId: z.string().optional(),
});
export type GraphDataSourceConfig = z.infer<typeof GraphDataSourceConfigSchema>;

export const GraphDetailVOSchema = z.object({
  graphId: z.string().optional(),
  name: z.string().optional(),
  projectId: z.string().optional(),
  nodes: z.array(GraphNodeDetailSchema).default([]),
  edges: z.array(GraphEdgeSchema).default([]),
  maxParallelism: z.number().optional(),
  dataSourceConfig: z.array(GraphDataSourceConfigSchema).optional(),
});
export type GraphDetailVO = z.infer<typeof GraphDetailVOSchema>;

export const GraphNodeStatusVOSchema = z.object({
  graphNodeId: z.string().optional(),
  taskId: z.string().optional(),
  jobId: z.string().optional(),
  status: z.string().optional(),
  progress: z.number().optional(),
  parties: z.array(z.record(z.any())).optional(),
});
export type GraphNodeStatusVO = z.infer<typeof GraphNodeStatusVOSchema>;

export const GraphStatusSchema = z.object({
  finished: z.boolean().optional(),
  nodes: z.array(GraphNodeStatusVOSchema).optional(),
});
export type GraphStatus = z.infer<typeof GraphStatusSchema>;

export const GraphNodeTaskLogsVOSchema = z.object({
  status: z.string().optional(),
  logs: z.array(z.string()).optional(),
});
export type GraphNodeTaskLogsVO = z.infer<typeof GraphNodeTaskLogsVOSchema>;

export const GraphNodeOutputVOSchema = z.object({
  type: z.string().optional(),
  codeName: z.string().optional(),
  tabs: z.record(z.any()).optional(),
  meta: z.record(z.any()).optional(),
  jobId: z.string().optional(),
  taskId: z.string().optional(),
  graphID: z.string().optional(),
  warning: z.array(z.string()).optional(),
  gmtCreate: z.string().optional(),
  gmtModified: z.string().optional(),
});
export type GraphNodeOutputVO = z.infer<typeof GraphNodeOutputVOSchema>;

// Component Schema (from /api/v1alpha1/component/*)
export const ComponentSummaryDefSchema = z.object({
  desc: z.string().optional(),
  domain: z.string().optional(),
  name: z.string().optional(),
  version: z.string().optional(),
});
export type ComponentSummaryDef = z.infer<typeof ComponentSummaryDefSchema>;

export const ComponentDefSchema = z.object({
  domain: z.string().optional(),
  name: z.string().optional(),
  version: z.string().optional(),
  desc: z.string().optional(),
  inputs: z.array(z.record(z.any())).optional(),
  outputs: z.array(z.record(z.any())).optional(),
  attrs: z.array(z.record(z.any())).optional(),
});
export type ComponentDef = z.infer<typeof ComponentDefSchema>;

export const CompListVOSchema = z.object({
  name: z.string().optional(),
  version: z.string().optional(),
  desc: z.string().optional(),
  comps: z.array(ComponentSummaryDefSchema).optional(),
});
export type CompListVO = z.infer<typeof CompListVOSchema>;
