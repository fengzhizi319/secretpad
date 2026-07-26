import React, { useState } from 'react';
import { Button, Badge } from '@secretpad/design-system';

export interface DAGNode {
  id: string;
  name: string;
  category: string;
  icon: string;
  status: 'Ready' | 'Running' | 'Success' | 'Failed';
  x: number;
  y: number;
  config?: Record<string, any>;
}

export interface DAGEdge {
  id: string;
  source: string;
  target: string;
}

export interface DAGCanvasProps {
  initialNodes?: DAGNode[];
  initialEdges?: DAGEdge[];
  onNodeSelect?: (node: DAGNode | null) => void;
}

const defaultNodes: DAGNode[] = [
  { id: 'node-1', name: 'Reader (Data Ingest)', category: 'IO', icon: '📥', status: 'Success', x: 60, y: 120 },
  { id: 'node-2', name: 'PSI (Intersection)', category: 'Privacy', icon: '🔒', status: 'Success', x: 260, y: 120 },
  { id: 'node-3', name: 'DP Feature Encoder', category: 'Preprocessing', icon: '⚙️', status: 'Success', x: 460, y: 70 },
  { id: 'node-4', name: 'Secure Aggregator', category: 'Security', icon: '🛡️', status: 'Running', x: 460, y: 180 },
  { id: 'node-5', name: 'XGBoost Trainer', category: 'ML', icon: '🤖', status: 'Ready', x: 660, y: 120 },
];

const defaultEdges: DAGEdge[] = [
  { id: 'e1-2', source: 'node-1', target: 'node-2' },
  { id: 'e2-3', source: 'node-2', target: 'node-3' },
  { id: 'e2-4', source: 'node-2', target: 'node-4' },
  { id: 'e3-5', source: 'node-3', target: 'node-5' },
  { id: 'e4-5', source: 'node-4', target: 'node-5' },
];

export const DAGNextWorkspace: React.FC<DAGCanvasProps> = ({
  initialNodes = defaultNodes,
  initialEdges = defaultEdges,
  onNodeSelect,
}) => {
  const [nodes, setNodes] = useState<DAGNode[]>(initialNodes);
  const [edges] = useState<DAGEdge[]>(initialEdges);
  const [selectedNode, setSelectedNode] = useState<DAGNode | null>(nodes[1]);
  const [zoom, setZoom] = useState(100);
  const [activeTab, setActiveTab] = useState<'config' | 'log' | 'result'>('config');

  const handleSelectNode = (node: DAGNode) => {
    setSelectedNode(node);
    if (onNodeSelect) onNodeSelect(node);
  };

  const handleAddNode = (category: string, name: string, icon: string) => {
    const newNode: DAGNode = {
      id: `node-${Date.now().toString().slice(-4)}`,
      name,
      category,
      icon,
      status: 'Ready',
      x: 250 + Math.random() * 50,
      y: 150 + Math.random() * 50,
    };
    setNodes([...nodes, newNode]);
  };

  return (
    <div className="flex flex-col h-full w-full bg-gray-900 text-gray-100 rounded-xl overflow-hidden border border-gray-800 shadow-2xl">
      {/* Canvas Top Bar */}
      <div className="h-12 bg-gray-950 border-b border-gray-800 px-4 flex items-center justify-between text-xs">
        <div className="flex items-center gap-3">
          <span className="font-semibold text-blue-400">⚡ DAG Pipeline Editor</span>
          <span className="text-gray-600">|</span>
          <span className="text-gray-400">Project: Medical FL Model v3</span>
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="ghost" onClick={() => setZoom(z => Math.max(50, z - 10))}>🔍 -</Button>
          <span className="font-mono text-gray-400 text-xs w-10 text-center">{zoom}%</span>
          <Button size="sm" variant="ghost" onClick={() => setZoom(z => Math.min(150, z + 10))}>🔍 +</Button>
          <div className="h-4 w-px bg-gray-800 mx-1" />
          <Button size="sm" variant="primary" icon={<span>▶</span>}>Execute Task</Button>
        </div>
      </div>

      <div className="flex flex-1 overflow-hidden relative">
        {/* Left Component Registry Panel */}
        <div className="w-56 bg-gray-950/80 border-r border-gray-800 flex flex-col">
          <div className="p-3 border-b border-gray-800 font-semibold text-xs text-gray-400 uppercase tracking-wider">
            Operator Library
          </div>
          <div className="flex-1 overflow-y-auto p-2 space-y-4 text-xs">
            <div>
              <div className="px-2 py-1 text-gray-500 font-semibold uppercase text-[10px]">Data I/O</div>
              <div
                onClick={() => handleAddNode('IO', 'CSV Reader', '📥')}
                className="p-2 mt-1 rounded bg-gray-900 border border-gray-800 hover:border-blue-500 hover:bg-gray-850 cursor-pointer flex items-center gap-2 transition-all"
              >
                <span>📥</span> CSV Reader
              </div>
            </div>

            <div>
              <div className="px-2 py-1 text-gray-500 font-semibold uppercase text-[10px]">Privacy & PSI</div>
              <div
                onClick={() => handleAddNode('Privacy', 'ECDH PSI', '🔒')}
                className="p-2 mt-1 rounded bg-gray-900 border border-gray-800 hover:border-blue-500 hover:bg-gray-850 cursor-pointer flex items-center gap-2 transition-all"
              >
                <span>🔒</span> ECDH PSI
              </div>
              <div
                onClick={() => handleAddNode('Privacy', 'DP Noise Generator', '🛡️')}
                className="p-2 mt-1 rounded bg-gray-900 border border-gray-800 hover:border-blue-500 hover:bg-gray-850 cursor-pointer flex items-center gap-2 transition-all"
              >
                <span>🛡️</span> DP Noise Generator
              </div>
            </div>

            <div>
              <div className="px-2 py-1 text-gray-500 font-semibold uppercase text-[10px]">Machine Learning</div>
              <div
                onClick={() => handleAddNode('ML', 'Secure XGBoost', '🤖')}
                className="p-2 mt-1 rounded bg-gray-900 border border-gray-800 hover:border-blue-500 hover:bg-gray-850 cursor-pointer flex items-center gap-2 transition-all"
              >
                <span>🤖</span> Secure XGBoost
              </div>
              <div
                onClick={() => handleAddNode('ML', 'Federated Logistic Reg', '📊')}
                className="p-2 mt-1 rounded bg-gray-900 border border-gray-800 hover:border-blue-500 hover:bg-gray-850 cursor-pointer flex items-center gap-2 transition-all"
              >
                <span>📊</span> Fed Logistic Reg
              </div>
            </div>
          </div>
        </div>

        {/* Center Canvas Area */}
        <div className="flex-1 bg-gray-900 relative overflow-hidden bg-[radial-gradient(#374151_1px,transparent_1px)] [background-size:16px_16px]">
          {/* SVG Connecting Edges */}
          <svg className="absolute inset-0 w-full h-full pointer-events-none z-0">
            {edges.map(e => {
              const srcNode = nodes.find(n => n.id === e.source);
              const tgtNode = nodes.find(n => n.id === e.target);
              if (!srcNode || !tgtNode) return null;
              const x1 = srcNode.x + 130;
              const y1 = srcNode.y + 25;
              const x2 = tgtNode.x;
              const y2 = tgtNode.y + 25;
              const cx = (x1 + x2) / 2;
              return (
                <path
                  key={e.id}
                  d={`M ${x1} ${y1} C ${cx} ${y1}, ${cx} ${y2}, ${x2} ${y2}`}
                  fill="none"
                  stroke="#3b82f6"
                  strokeWidth="2"
                  strokeDasharray="4 2"
                />
              );
            })}
          </svg>

          {/* Render Nodes */}
          {nodes.map(node => {
            const isSelected = selectedNode?.id === node.id;
            return (
              <div
                key={node.id}
                onClick={() => handleSelectNode(node)}
                style={{ left: `${node.x}px`, top: `${node.y}px` }}
                className={`absolute w-36 p-3 rounded-lg bg-gray-950 border-2 cursor-pointer shadow-lg transition-all z-10 ${
                  isSelected
                    ? 'border-blue-500 shadow-blue-500/20 ring-2 ring-blue-500/30'
                    : 'border-gray-800 hover:border-gray-700'
                }`}
              >
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-base">{node.icon}</span>
                  <span className="font-semibold text-xs text-gray-200 truncate">{node.name}</span>
                </div>
                <div className="flex items-center justify-between text-[10px] text-gray-400">
                  <span>{node.category}</span>
                  <span className={`px-1.5 py-0.5 rounded text-[9px] ${
                    node.status === 'Success' ? 'bg-emerald-950 text-emerald-400' :
                    node.status === 'Running' ? 'bg-blue-950 text-blue-400 animate-pulse' :
                    'bg-gray-800 text-gray-400'
                  }`}>
                    {node.status}
                  </span>
                </div>
              </div>
            );
          })}
        </div>

        {/* Right Configuration Inspector */}
        {selectedNode && (
          <div className="w-72 bg-gray-950 border-l border-gray-800 flex flex-col">
            <div className="flex border-b border-gray-800 text-xs font-medium">
              <button
                onClick={() => setActiveTab('config')}
                className={`flex-1 py-3 text-center border-b-2 ${activeTab === 'config' ? 'border-blue-500 text-blue-400' : 'border-transparent text-gray-400'}`}
              >
                Parameters
              </button>
              <button
                onClick={() => setActiveTab('log')}
                className={`flex-1 py-3 text-center border-b-2 ${activeTab === 'log' ? 'border-blue-500 text-blue-400' : 'border-transparent text-gray-400'}`}
              >
                Logs
              </button>
            </div>

            <div className="p-4 flex-1 overflow-y-auto text-xs space-y-4">
              <div>
                <label className="text-gray-400 block mb-1">Node Identifier</label>
                <div className="p-2 rounded bg-gray-900 border border-gray-800 font-mono text-gray-300">
                  {selectedNode.id}
                </div>
              </div>

              <div>
                <label className="text-gray-400 block mb-1">Operator Name</label>
                <input
                  type="text"
                  value={selectedNode.name}
                  onChange={e => setSelectedNode({ ...selectedNode, name: e.target.value })}
                  className="w-full p-2 rounded bg-gray-900 border border-gray-800 text-gray-200 focus:outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="text-gray-400 block mb-1">Execution Status</label>
                <Badge status={selectedNode.status === 'Success' ? 'success' : 'processing'}>
                  {selectedNode.status}
                </Badge>
              </div>

              <div>
                <label className="text-gray-400 block mb-1">Security / Privacy Protocol</label>
                <select className="w-full p-2 rounded bg-gray-900 border border-gray-800 text-gray-200 focus:outline-none focus:border-blue-500">
                  <option>SS (Secret Sharing)</option>
                  <option>HE (Paillier Homomorphic)</option>
                  <option>TEE Enclave Protection</option>
                </select>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
