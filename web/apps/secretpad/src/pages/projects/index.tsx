import React, { useEffect, useState } from 'react';
import { Card, Button, Badge, Modal } from '@secretpad/design-system';
import { apiClient, Project } from '@secretpad/api-client';

export const ProjectsPage: React.FC<{ onNavigate: (path: string) => void }> = ({ onNavigate }) => {
  const [projects, setProjects] = useState<Project[]>([]);
  const [search, setSearch] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  
  // New Project Form
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [computeMode, setComputeMode] = useState<'MPC' | 'FL' | 'TEE' | 'HE'>('FL');

  useEffect(() => {
    apiClient.getProjects().then(setProjects);
  }, []);

  const handleCreateProject = async (e: React.FormEvent) => {
    e.preventDefault();
    const newProj = await apiClient.createProject({ name, description, computeMode, nodes: ['alice', 'bob'] });
    setProjects([newProj, ...projects]);
    setIsModalOpen(false);
    setName('');
    setDescription('');
  };

  const filteredProjects = projects.filter(p => p.name.toLowerCase().includes(search.toLowerCase()));

  return (
    <div className="space-y-6">
      {/* Header & Actions */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">Collaborative Privacy Projects</h2>
          <p className="text-xs text-gray-500">Manage federated learning, MPC, and TEE privacy compute spaces</p>
        </div>
        <div className="flex items-center gap-3">
          <input
            type="text"
            placeholder="Search project name..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="px-3 py-1.5 rounded-lg text-xs bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          />
          <Button variant="primary" size="md" icon={<span>＋</span>} onClick={() => setIsModalOpen(true)}>
            Create Project
          </Button>
        </div>
      </div>

      {/* Project Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
        {filteredProjects.map((project) => (
          <Card key={project.projectId} className="hover:shadow-md transition-all flex flex-col justify-between">
            <div>
              <div className="flex items-center justify-between mb-3">
                <span className="px-2.5 py-1 rounded-full text-xs font-mono font-semibold bg-blue-50 dark:bg-blue-950 text-blue-600 dark:text-blue-400 border border-blue-200 dark:border-blue-800">
                  Mode: {project.computeMode}
                </span>
                <Badge status={project.status === 'ACTIVE' ? 'success' : 'default'}>
                  {project.status}
                </Badge>
              </div>

              <h3 className="font-bold text-base text-gray-900 dark:text-gray-100 mb-1.5">{project.name}</h3>
              <p className="text-xs text-gray-500 dark:text-gray-400 line-clamp-2 mb-4">{project.description || 'No description'}</p>
            </div>

            <div className="pt-3 border-t border-gray-100 dark:border-gray-800 flex items-center justify-between text-xs">
              <div className="flex items-center gap-1 text-gray-500">
                <span>Joined Nodes:</span>
                <span className="font-semibold text-gray-700 dark:text-gray-300">{project.nodes.join(', ')}</span>
              </div>
              <Button size="sm" variant="ghost" onClick={() => onNavigate('/dag')}>
                Open DAG Editor →
              </Button>
            </div>
          </Card>
        ))}
      </div>

      {/* Create Project Modal */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title="Create Privacy Computing Project"
        footer={
          <>
            <Button variant="ghost" onClick={() => setIsModalOpen(false)}>Cancel</Button>
            <Button variant="primary" onClick={handleCreateProject}>Create Project</Button>
          </>
        }
      >
        <form onSubmit={handleCreateProject} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">Project Name</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Risk Model Joint Training"
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              required
            />
          </div>

          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">Compute Mode</label>
            <select
              value={computeMode}
              onChange={(e) => setComputeMode(e.target.value as any)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              <option value="FL">Federated Learning (FL)</option>
              <option value="MPC">Multi-Party Computation (MPC / SPU)</option>
              <option value="TEE">Trusted Execution Environment (TEE)</option>
              <option value="HE">Homomorphic Encryption (HEU)</option>
            </select>
          </div>

          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">Description</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              placeholder="Describe collaboration purpose and privacy requirements..."
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            />
          </div>
        </form>
      </Modal>
    </div>
  );
};
