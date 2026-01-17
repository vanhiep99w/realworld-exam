import { apiClient, ApiRequestError } from './apiClient';

const API_BASE = '/api/s3';

export const MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
export const ALLOWED_CONTENT_TYPES = [
  'image/jpeg', 'image/png', 'image/gif', 'image/webp',
  'application/pdf',
  'application/zip', 'application/x-zip-compressed'
];

export interface PresignedUrlResponse {
  url: string;
  key: string;
}

export interface PresignedPostResponse {
  url: string;
  fields: Record<string, string>;
  key: string;
}

export interface S3File {
  key: string;
  size: number;
  lastModified: string;
}

export { ApiRequestError };

export async function getPresignedPutUrl(key: string, contentType: string, fileSize: number): Promise<PresignedUrlResponse> {
  const { data } = await apiClient.get<PresignedUrlResponse>(
    `${API_BASE}/presigned-url/put`,
    { params: { key, contentType, fileSize } }
  );
  return data;
}

export async function getPresignedPostUrl(key: string, contentType: string): Promise<PresignedPostResponse> {
  const { data } = await apiClient.get<PresignedPostResponse>(
    `${API_BASE}/presigned-url/post`,
    { params: { key, contentType } }
  );
  return data;
}

export async function uploadWithPut(url: string, file: File): Promise<void> {
  const res = await fetch(url, {
    method: 'PUT',
    body: file,
    headers: { 
      'Content-Type': file.type || 'application/octet-stream'
    }
  });
  if (!res.ok) throw new Error('Upload failed');
}

export async function uploadWithPost(url: string, fields: Record<string, string>, file: File): Promise<void> {
  const formData = new FormData();
  Object.entries(fields).forEach(([k, v]) => formData.append(k, v));
  formData.append('file', file);
  const res = await fetch(url, { method: 'POST', body: formData });
  if (!res.ok) throw new Error('Upload failed');
}

export async function getPresignedGetUrl(key: string): Promise<PresignedUrlResponse> {
  const { data } = await apiClient.get<PresignedUrlResponse>(
    `${API_BASE}/presigned-url/get`,
    { params: { key } }
  );
  return data;
}

export async function listFiles(): Promise<S3File[]> {
  const { data } = await apiClient.get<S3File[]>(`${API_BASE}/files`);
  return data;
}

export function validateFile(file: File): string | null {
  if (file.size > MAX_FILE_SIZE) {
    return `File too large: ${(file.size / 1024 / 1024).toFixed(2)}MB. Max: 10MB`;
  }
  if (!ALLOWED_CONTENT_TYPES.includes(file.type)) {
    return `File type not allowed: ${file.type || 'unknown'}. Allowed: images, PDF, ZIP`;
  }
  return null;
}
