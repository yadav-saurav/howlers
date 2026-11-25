import { describe, expect, it } from 'vitest';
import { accessFor, curlFor, fieldsOf, filterGroups, groupEndpoints, titleFor, typeLabel, type OpenApiDoc } from './openapi';

const doc: OpenApiDoc = {
  paths: {
    '/api/v1/posts': {
      post: {
        tags: ['Posts'],
        operationId: 'create',
        requestBody: { content: { 'application/json': { schema: { $ref: '#/components/schemas/CreatePostRequest' } } } },
        responses: { '201': { description: 'Created', content: { '*/*': { schema: { $ref: '#/components/schemas/PostDetail' } } } } },
      },
    },
    '/api/v1/posts/{id}': { get: { tags: ['Posts'], operationId: 'get' }, delete: { tags: ['Posts'], operationId: 'delete_1' } },
    '/api/v1/users/{username}/mute': { put: { tags: ['Mutes'], operationId: 'muteUser' } },
    '/api/v1/auth/login': { post: { tags: ['Auth'], operationId: 'login' } },
    '/api/v1/podcasts': { get: { tags: ['Podcasts'], operationId: 'list' } },
    '/api/v1/admin/users': { get: { tags: ['Admin'], operationId: 'users' } },
  },
  components: {
    schemas: {
      CreatePostRequest: {
        type: 'object',
        required: ['communitySlug', 'type', 'title'],
        properties: {
          communitySlug: { type: 'string', minLength: 1 },
          type: { type: 'string', pattern: 'TEXT|LINK|IMAGE|POLL' },
          title: { type: 'string', maxLength: 300, minLength: 0 },
          tags: { type: 'array', items: { type: 'string' }, maxItems: 5 },
        },
      },
    },
  },
};

describe('api docs from OpenAPI', () => {
  it('groups in the documented order and hides switched-off areas', () => {
    const groups = groupEndpoints(doc);
    expect(groups.map((g) => g.name)).toEqual(['Auth', 'Posts', 'Mutes', 'Admin']);
    expect(groups.find((g) => g.name === 'Posts')!.endpoints.map((e) => `${e.method} ${e.path}`)).toEqual([
      'post /api/v1/posts', 'get /api/v1/posts/{id}', 'delete /api/v1/posts/{id}',
    ]);
  });

  it('writes readable titles', () => {
    expect(titleFor('put', '/api/v1/users/{username}/mute', 'muteUser')).toBe('Mute user');
    expect(titleFor('post', '/api/v1/posts', 'create')).toBe('Create post');
    expect(titleFor('delete', '/api/v1/posts/{id}', 'delete_1')).toBe('Delete post');
    expect(titleFor('get', '/api/v1/posts/{postId}/comments', 'list_1')).toBe('List comments');
    expect(titleFor('post', '/api/v1/communities', 'create_4')).toBe('Create community');
    expect(titleFor('post', '/api/v1/auth/login', 'login')).toBe('Login');
    expect(titleFor('put', '/api/v1/users/{username}/block', 'block')).toBe('Block');
  });

  it('labels who can call an endpoint', () => {
    expect(accessFor('post', '/api/v1/auth/login')).toBe('public');
    expect(accessFor('get', '/api/v1/admin/users')).toBe('admin');
    expect(accessFor('get', '/api/v1/users/me')).toBe('required');
    expect(accessFor('post', '/api/v1/posts')).toBe('required');
    expect(accessFor('get', '/api/v1/posts/{id}')).toBe('optional');
  });

  it('lists request fields with types and limits', () => {
    const fields = fieldsOf(doc, { $ref: '#/components/schemas/CreatePostRequest' });
    expect(fields).toEqual([
      { name: 'communitySlug', type: 'string', required: true, limits: '' },
      { name: 'type', type: 'TEXT | LINK | IMAGE | POLL', required: true, limits: '' },
      { name: 'title', type: 'string', required: true, limits: 'max 300 characters' },
      { name: 'tags', type: 'string[]', required: false, limits: 'up to 5' },
    ]);
    expect(typeLabel({ $ref: '#/components/schemas/PostDetail' })).toBe('PostDetail');
  });

  it('searches across path, title and group', () => {
    const groups = groupEndpoints(doc);
    expect(filterGroups(groups, 'mute').flatMap((g) => g.endpoints.map((e) => e.path))).toEqual(['/api/v1/users/{username}/mute']);
    expect(filterGroups(groups, 'delete posts').flatMap((g) => g.endpoints.map((e) => e.method))).toEqual(['delete']);
    expect(filterGroups(groups, '')).toHaveLength(4);
  });

  it('builds a curl example', () => {
    const e = groupEndpoints(doc).find((g) => g.name === 'Posts')!.endpoints[0];
    const curl = curlFor(e, 'https://example.com', doc);
    expect(curl).toContain('curl -X POST "https://example.com/api/v1/posts"');
    expect(curl).toContain('Authorization: Bearer $TOKEN');
    expect(curl).toContain(`-d '{"communitySlug":"…","type":"…","title":"…"}'`);
  });
});
