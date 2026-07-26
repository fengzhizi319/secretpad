import { describe, it, expect, vi, beforeEach } from 'vitest';
import { apiClient } from './client';
import { api } from './api';

vi.mock('./api', () => ({
  api: {
    POST: vi.fn(),
    use: vi.fn(),
  },
}));

const mockPost = vi.mocked(api.POST);

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
});

describe('apiClient.login', () => {
  it('stores the returned token and returns the user', async () => {
    const user = {
      token: 'abc123',
      name: 'admin',
      ownerId: 'kuscia-system',
      platformType: 'CENTER',
      platformNodeId: 'kuscia-system',
      ownerType: 'CENTER',
    };

    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0, msg: 'success' }, data: user },
      error: undefined,
      response: new Response(),
    } as any);

    const result = await apiClient.login('admin', 'hash');
    expect(result).toEqual(user);
    expect(localStorage.getItem('secretpad-token')).toBe('abc123');
    expect(mockPost).toHaveBeenCalledWith('/api/login', {
      body: { name: 'admin', passwordHash: 'hash' },
    });
  });

  it('throws when backend returns a non-zero status', async () => {
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 202011601, msg: 'bad password' }, data: null },
      error: undefined,
      response: new Response(),
    } as any);

    await expect(apiClient.login('admin', 'hash')).rejects.toThrow('bad password');
  });
});

describe('apiClient.getNodes', () => {
  it('normalizes backend NodeVO fields', async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        status: { code: 0 },
        data: [
          {
            nodeId: 'alice',
            nodeName: 'Alice Node',
            nodeStatus: 'Ready',
            type: 'embedded',
            netAddress: '127.0.0.1:28080',
            gmtCreate: '2026-07-26T11:02:33+08:00',
          },
        ],
      },
      error: undefined,
      response: new Response(),
    } as any);

    const nodes = await apiClient.getNodes();
    expect(nodes).toHaveLength(1);
    expect(nodes[0]).toMatchObject({
      nodeId: 'alice',
      nodeName: 'Alice Node',
      name: 'Alice Node',
      status: 'Ready',
      nodeStatus: 'Ready',
    });
  });
});

describe('apiClient.getDataSources', () => {
  it('unwraps paginated datasource info list', async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        status: { code: 0 },
        data: {
          infos: [
            {
              datasourceId: 'http-data-source',
              name: 'HTTP Source',
              type: 'HTTP',
              nodes: [{ nodeId: 'alice', nodeName: 'alice' }],
            },
          ],
        },
      },
      error: undefined,
      response: new Response(),
    } as any);

    const sources = await apiClient.getDataSources('alice');
    expect(sources).toHaveLength(1);
    expect(sources[0].datasourceId).toBe('http-data-source');
  });
});

describe('apiClient.logout', () => {
  it('clears the stored token', async () => {
    localStorage.setItem('secretpad-token', 'abc');
    mockPost.mockResolvedValueOnce({ data: { status: { code: 0 } }, error: undefined, response: new Response() } as any);
    await apiClient.logout();
    expect(localStorage.getItem('secretpad-token')).toBeNull();
  });
});

describe('apiClient graph operations', () => {
  it('updates full graph', async () => {
    mockPost.mockResolvedValueOnce({ data: { status: { code: 0 } }, error: undefined, response: new Response() } as any);
    await apiClient.updateGraph('proj-1', 'graph-1', [], []);
    expect(mockPost).toHaveBeenCalledWith('/api/v1alpha1/graph/update', {
      body: { projectId: 'proj-1', graphId: 'graph-1', nodes: [], edges: [] },
    });
  });

  it('updates a single graph node', async () => {
    mockPost.mockResolvedValueOnce({ data: { status: { code: 0 } }, error: undefined, response: new Response() } as any);
    const node = { graphNodeId: 'n1', codeName: 'ml.train/sgb_train' };
    await apiClient.updateGraphNode('proj-1', 'graph-1', node);
    expect(mockPost).toHaveBeenCalledWith('/api/v1alpha1/graph/node/update', {
      body: { projectId: 'proj-1', graphId: 'graph-1', node },
    });
  });

  it('fetches graph node status', async () => {
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0 }, data: { finished: false, nodes: [] } },
      error: undefined,
      response: new Response(),
    } as any);
    const status = await apiClient.getGraphNodeStatus('proj-1', 'graph-1');
    expect(status.finished).toBe(false);
  });

  it('fetches graph node logs', async () => {
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0 }, data: { status: 'RUNNING', logs: ['log line'] } },
      error: undefined,
      response: new Response(),
    } as any);
    const logs = await apiClient.getGraphNodeLogs('proj-1', 'graph-1', 'n1');
    expect(logs.logs).toEqual(['log line']);
  });

  it('fetches graph node output', async () => {
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0 }, data: { type: 'table', codeName: 'ml.train/sgb_train' } },
      error: undefined,
      response: new Response(),
    } as any);
    const output = await apiClient.getGraphNodeOutput('proj-1', 'graph-1', 'n1', 'n1-output-0');
    expect(output.type).toBe('table');
  });

  it('batch fetches component definitions', async () => {
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0 }, data: { 'read_data/datatable': { name: 'read_data' } } },
      error: undefined,
      response: new Response(),
    } as any);
    const defs = await apiClient.batchGetComponent([{ domain: 'read_data', name: 'datatable' }]);
    expect(defs['read_data/datatable'].name).toBe('read_data');
  });
});

describe('apiClient nodeRoute / inst / p2p mappings', () => {
  it('unwraps paginated node routes', async () => {
    mockPost.mockResolvedValueOnce({
      data: {
        status: { code: 0 },
        data: { data: [{ routeId: 'r1', srcNodeId: 'alice', dstNodeId: 'bob' }], totalCount: 1 },
      },
      error: undefined,
      response: new Response(),
    } as any);
    const res = await apiClient.listNodeRoutes({ pageNumber: 1, pageSize: 10 });
    expect(res.totalCount).toBe(1);
    expect(res.data[0].routeId).toBe('r1');
  });

  it('maps institution info', async () => {
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0 }, data: { instId: 'inst-1', instName: 'Inst One', localNodeId: 'alice' } },
      error: undefined,
      response: new Response(),
    } as any);
    const inst = await apiClient.getInst('inst-1');
    expect(inst.instName).toBe('Inst One');
  });

  it('lists p2p projects', async () => {
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0 }, data: [{ projectId: 'p1', projectName: 'P2P Proj', computeMode: 'MPC' }] },
      error: undefined,
      response: new Response(),
    } as any);
    const projects = await apiClient.listP2pProjects();
    expect(projects).toHaveLength(1);
    expect(projects[0].projectName).toBe('P2P Proj');
  });
});

describe('Zod runtime validation failure path', () => {
  it('throws a descriptive error when a required field is missing', async () => {
    // ProjectVOSchema requires `projectId`; omit it to trigger safeParse failure.
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0 }, data: [{ projectName: 'no-id' }] },
      error: undefined,
      response: new Response(),
    } as any);
    await expect(apiClient.listP2pProjects()).rejects.toThrow(/API schema validation failed/);
  });

  it('includes the offending field path in the error message', async () => {
    mockPost.mockResolvedValueOnce({
      data: { status: { code: 0 }, data: [{ projectName: 'no-id' }] },
      error: undefined,
      response: new Response(),
    } as any);
    await expect(apiClient.listP2pProjects()).rejects.toThrow(/projectId/);
  });
});
