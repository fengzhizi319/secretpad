import { z } from 'zod';

// User Schema
export const UserSchema = z.object({
  ownerId: z.string(),
  name: z.string(),
  role: z.enum(['ADMIN', 'DEVELOPER', 'GUEST']),
  token: z.string().optional(),
});
export type User = z.infer<typeof UserSchema>;

// Platform Schema
export const PlatformSchema = z.object({
  platformType: z.enum(['CENTER', 'EDGE', 'P2P']),
  nodeId: z.string().optional(),
});
export type Platform = z.infer<typeof PlatformSchema>;

// Node Schema
export const NodeSchema = z.object({
  nodeId: z.string(),
  name: z.string(),
  controlNodeId: z.string().optional(),
  type: z.enum(['CENTER', 'EDGE', 'AUTONOMY']),
  status: z.enum(['Ready', 'NotReady', 'Offline']),
  ip: z.string().optional(),
  cpu: z.number().optional(),
  memory: z.number().optional(),
  createTime: z.string(),
});
export type Node = z.infer<typeof NodeSchema>;

// Project Schema
export const ProjectSchema = z.object({
  projectId: z.string(),
  name: z.string(),
  description: z.string().optional(),
  computeMode: z.enum(['MPC', 'FL', 'TEE', 'HE']),
  nodes: z.array(z.string()),
  status: z.enum(['ACTIVE', 'ARCHIVED', 'SUSPENDED']),
  jobCount: z.number().default(0),
  createTime: z.string(),
});
export type Project = z.infer<typeof ProjectSchema>;

// DataTable Schema
export const DataTableSchema = z.object({
  tableName: z.string(),
  tableId: z.string(),
  nodeId: z.string(),
  datasourceId: z.string().optional(),
  columns: z.array(z.object({
    name: z.string(),
    type: z.string(),
    comment: z.string().optional(),
    classification: z.string().optional(),
  })),
  rowCount: z.number(),
  status: z.enum(['Available', 'Processing', 'Unavailable']),
  createTime: z.string(),
});
export type DataTable = z.infer<typeof DataTableSchema>;

// DataSource Schema
export const DataSourceSchema = z.object({
  datasourceId: z.string(),
  name: z.string(),
  type: z.enum(['ODPS', 'MYSQL', 'LOCAL', 'HTTP']),
  nodeId: z.string(),
  status: z.enum(['Available', 'Error']),
  createTime: z.string(),
});
export type DataSource = z.infer<typeof DataSourceSchema>;

// Job Execution Schema
export const JobExecutionSchema = z.object({
  jobId: z.string(),
  projectId: z.string(),
  name: z.string(),
  status: z.enum(['RUNNING', 'SUCCEEDED', 'FAILED', 'PENDING']),
  duration: z.string().optional(),
  createTime: z.string(),
});
export type JobExecution = z.infer<typeof JobExecutionSchema>;
