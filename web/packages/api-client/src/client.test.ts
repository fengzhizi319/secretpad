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
