const API_BASE = 'http://localhost:8080/exports';

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
  const res = await fetch(`${API_BASE}/users`, { method: 'POST' });
  if (!res.ok) {
    const err = await res.json();
    throw new Error(err.error || 'Failed to start export');
  }
  return res.json();
}

export async function getExportStatus(jobId: string): Promise<ExportJob> {
  const res = await fetch(`${API_BASE}/${jobId}`);
  if (!res.ok) {
    const err = await res.json();
    throw new Error(err.error || 'Failed to get export status');
  }
  return res.json();
}

export async function getDownloadUrl(jobId: string): Promise<DownloadUrlResponse> {
  const res = await fetch(`${API_BASE}/${jobId}/download-url`);
  if (!res.ok) {
    const err = await res.json();
    throw new Error(err.error || 'Failed to get download URL');
  }
  return res.json();
}
