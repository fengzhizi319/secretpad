import React, { useEffect, useState } from 'react';
import { Card, Badge, Button } from '@secretpad/design-system';
import { apiClient, Node, Project, JobExecution } from '@secretpad/api-client';

export const DashboardPage: React.FC<{ onNavigate: (path: string) => void }> = ({ onNavigate }) => {
  const [nodes, setNodes] = useState<Node[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [jobs, setJobs] = useState<JobExecution[]>([]);

  useEffect(() => {
    apiClient.getNodes().then(setNodes);
    apiClient.getProjects().then(setProjects);
    apiClient.getJobs().then(setJobs);
  }, []);

  return (
    <div className="space-y-6">
      {/* Stat Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <Card className="hover:border-blue-500/50 transition-all cursor-pointer" bodyClassName="p-4">
          <div className="flex items-center justify-between text-xs text-gray-500 font-medium">
            <span>Collaborative Projects</span>
            <span className="p-1.5 rounded-lg bg-blue-50 dark:bg-blue-950 text-blue-600">📁</span>
          </div>
          <div className="mt-2 text-2xl font-bold text-gray-900 dark:text-gray-100">{projects.length}</div>
          <div className="mt-1 text-[11px] text-emerald-600 flex items-center gap-1 font-medium">
            <span>↑ 100% Active Mode</span>
          </div>
        </Card>

        <Card className="hover:border-blue-500/50 transition-all cursor-pointer" bodyClassName="p-4">
          <div className="flex items-center justify-between text-xs text-gray-500 font-medium">
            <span>Registered Nodes</span>
            <span className="p-1.5 rounded-lg bg-purple-50 dark:bg-purple-950 text-purple-600">🖥️</span>
          </div>
          <div className="mt-2 text-2xl font-bold text-gray-900 dark:text-gray-100">{nodes.length}</div>
          <div className="mt-1 text-[11px] text-gray-500">
            {nodes.filter(n => n.status === 'Ready').length} Ready / 0 Offline
          </div>
        </Card>

        <Card className="hover:border-blue-500/50 transition-all cursor-pointer" bodyClassName="p-4">
          <div className="flex items-center justify-between text-xs text-gray-500 font-medium">
            <span>Job Executions</span>
            <span className="p-1.5 rounded-lg bg-emerald-50 dark:bg-emerald-950 text-emerald-600">⚡</span>
          </div>
          <div className="mt-2 text-2xl font-bold text-gray-900 dark:text-gray-100">45</div>
          <div className="mt-1 text-[11px] text-emerald-600 font-medium">
            98.5% Success Rate
          </div>
        </Card>

        <Card className="hover:border-blue-500/50 transition-all cursor-pointer" bodyClassName="p-4">
          <div className="flex items-center justify-between text-xs text-gray-500 font-medium">
            <span>Kuscia Cluster Load</span>
            <span className="p-1.5 rounded-lg bg-amber-50 dark:bg-amber-950 text-amber-600">🛡️</span>
          </div>
          <div className="mt-2 text-2xl font-bold text-gray-900 dark:text-gray-100">24.5%</div>
          <div className="mt-1 text-[11px] text-gray-500">
            CPU: 8/36 Cores • RAM: 16/64 GB
          </div>
        </Card>
      </div>

      {/* Main Content Layout */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left Column: Recent Jobs & Project List */}
        <div className="lg:col-span-2 space-y-6">
          <Card
            title="Recent Job Executions"
            extra={<Button size="sm" variant="link" onClick={() => onNavigate('/dag')}>Launch New DAG Pipeline →</Button>}
          >
            <div className="divide-y divide-gray-100 dark:divide-gray-800">
              {jobs.map((job) => (
                <div key={job.jobId} className="py-3 flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-xs ${
                      job.status === 'RUNNING' ? 'bg-blue-50 text-blue-600 dark:bg-blue-950/50 animate-spin' :
                      job.status === 'SUCCEEDED' ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/50' : 'bg-gray-100'
                    }`}>
                      {job.status === 'RUNNING' ? '🔄' : '✓'}
                    </div>
                    <div>
                      <div className="font-semibold text-sm text-gray-800 dark:text-gray-200">{job.name}</div>
                      <div className="text-xs text-gray-400 font-mono">Job ID: {job.jobId} • Duration: {job.duration || '-'}</div>
                    </div>
                  </div>
                  <Badge status={job.status === 'RUNNING' ? 'processing' : 'success'}>
                    {job.status}
                  </Badge>
                </div>
              ))}
            </div>
          </Card>

          <Card
            title="Active Privacy Projects"
            extra={<Button size="sm" variant="link" onClick={() => onNavigate('/projects')}>View All Projects →</Button>}
          >
            <div className="space-y-3">
              {projects.map((proj) => (
                <div
                  key={proj.projectId}
                  onClick={() => onNavigate('/projects')}
                  className="p-3.5 rounded-xl border border-gray-100 dark:border-gray-800 hover:border-blue-500/40 bg-gray-50/50 dark:bg-gray-850/50 cursor-pointer transition-all flex items-center justify-between"
                >
                  <div>
                    <div className="font-semibold text-sm text-gray-900 dark:text-gray-100 flex items-center gap-2">
                      {proj.name}
                      <span className="px-2 py-0.5 rounded text-[10px] bg-blue-100 dark:bg-blue-900/60 text-blue-700 dark:text-blue-300 font-mono">
                        {proj.computeMode} Mode
                      </span>
                    </div>
                    <div className="text-xs text-gray-500 mt-1 line-clamp-1">{proj.description}</div>
                  </div>
                  <div className="text-right text-xs text-gray-400">
                    <div>{proj.nodes.length} Nodes Joined</div>
                    <div className="text-[11px] text-blue-600 font-medium mt-0.5">{proj.jobCount} Jobs Ran</div>
                  </div>
                </div>
              ))}
            </div>
          </Card>
        </div>

        {/* Right Column: Node Topology & Quick Actions */}
        <div className="space-y-6">
          <Card title="Node Topology Status">
            <div className="space-y-3">
              {nodes.map((node) => (
                <div key={node.nodeId} className="p-3 rounded-lg border border-gray-100 dark:border-gray-800 flex items-center justify-between">
                  <div className="flex items-center gap-2.5">
                    <div className="w-2.5 h-2.5 rounded-full bg-emerald-500" />
                    <div>
                      <div className="font-semibold text-xs text-gray-800 dark:text-gray-200">{node.name}</div>
                      <div className="text-[10px] text-gray-400 font-mono">{node.ip}</div>
                    </div>
                  </div>
                  <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-gray-100 dark:bg-gray-800 text-gray-600 dark:text-gray-300">
                    {node.type}
                  </span>
                </div>
              ))}
            </div>
          </Card>

          <Card title="Quick Actions">
            <div className="grid grid-cols-2 gap-2.5">
              <Button onClick={() => onNavigate('/dag')} variant="outline" className="justify-start text-xs p-3">
                ⚡ Create DAG Workflow
              </Button>
              <Button onClick={() => onNavigate('/projects')} variant="outline" className="justify-start text-xs p-3">
                ➕ New Project
              </Button>
              <Button onClick={() => onNavigate('/data-tables')} variant="outline" className="justify-start text-xs p-3">
                🗄️ Import Data Table
              </Button>
              <Button onClick={() => onNavigate('/nodes')} variant="outline" className="justify-start text-xs p-3">
                🖥️ Register Node
              </Button>
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
};
