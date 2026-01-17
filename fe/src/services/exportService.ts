import { apiClient } from './apiClient';

const API_BASE = '/exports';

export type ExportStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ExportJob {
  id: string;
  status: ExportStatus;
  totalRecords: number | null;
  processedRecords: number;
  progressPercent: number;
  s3Key: string | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  // Metrics
  fileSizeBytes: number | null;
  fileSizeFormatted: string | null;
  uncompressedSizeBytes: number | null;
  uncompressedSizeFormatted: string | null;
  compressionPercent: number | null;
  rowsPerSecond: number | null;
  durationMs: number | null;
  durationFormatted: string | null;
}

export interface StartExportResponse {
  jobId: string;
  message: string;
  statusUrl: string;
}

export interface DownloadUrlResponse {
  downloadUrl: string;
}

export async function startExport(): Promise<StartExportResponse> {
  const { data } = await apiClient.post<StartExportResponse>(`${API_BASE}/users`);
  return data;
}

export async function getExportStatus(jobId: string): Promise<ExportJob> {
  const { data } = await apiClient.get<ExportJob>(`${API_BASE}/${jobId}`);
  return data;
}

export async function getDownloadUrl(jobId: string): Promise<DownloadUrlResponse> {
  const { data } = await apiClient.get<DownloadUrlResponse>(`${API_BASE}/${jobId}/download-url`);
  return data;
}
