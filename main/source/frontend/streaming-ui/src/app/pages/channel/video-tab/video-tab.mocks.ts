/**
 * Mock data for VideoTab rails that are deferred to Phase 8.
 *
 * TODO(Phase-8): Remove this file when the real upload + playlist APIs exist.
 * Search for TODO(Phase-8) to find all deferred mock references.
 */

export interface VideoCardMock {
  id: string;
  title: string;
  thumbnailPlaceholder: string;
  views: number;
  createdAt: string;
  duration: string;
}

export interface PlaylistCardMock {
  id: string;
  title: string;
  videoCount: number;
  updatedAt: string;
}

export const MOCK_UPLOAD_VIDEOS: readonly VideoCardMock[] = [
  {
    id: 'mock-upload-1',
    title: 'My First Edited Video',
    thumbnailPlaceholder: 'video',
    views: 1200,
    createdAt: '2026-06-15T10:00:00Z',
    duration: '12:34',
  },
  {
    id: 'mock-upload-2',
    title: 'Highlights Compilation',
    thumbnailPlaceholder: 'video',
    views: 3400,
    createdAt: '2026-06-10T08:30:00Z',
    duration: '08:17',
  },
  {
    id: 'mock-upload-3',
    title: 'Tutorial: Getting Started',
    thumbnailPlaceholder: 'video',
    views: 890,
    createdAt: '2026-05-28T14:00:00Z',
    duration: '25:02',
  },
  {
    id: 'mock-upload-4',
    title: 'Behind the Scenes',
    thumbnailPlaceholder: 'video',
    views: 2100,
    createdAt: '2026-05-20T16:45:00Z',
    duration: '06:11',
  },
  {
    id: 'mock-upload-5',
    title: 'Q&A Session',
    thumbnailPlaceholder: 'video',
    views: 5600,
    createdAt: '2026-07-01T12:00:00Z',
    duration: '45:00',
  },
];

export const MOCK_PLAYLISTS: readonly PlaylistCardMock[] = [
  {
    id: 'mock-playlist-1',
    title: 'Best Moments 2026',
    videoCount: 8,
    updatedAt: '2026-07-10T09:00:00Z',
  },
  {
    id: 'mock-playlist-2',
    title: 'Tutorials & Guides',
    videoCount: 12,
    updatedAt: '2026-07-05T14:30:00Z',
  },
  {
    id: 'mock-playlist-3',
    title: 'Stream Archive',
    videoCount: 5,
    updatedAt: '2026-06-28T11:00:00Z',
  },
];
