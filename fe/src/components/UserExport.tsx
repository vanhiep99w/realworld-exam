import { useState, useEffect, useRef } from 'react';
import {
  startExport,
  getExportStatus,
  getDownloadUrl,
  type ExportJob,
  type ExportStatus
} from '@/services/exportService';

export function UserExport() {
  const [job, setJob] = useState<ExportJob | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const pollRef = useRef<number | null>(null);

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  useEffect(() => {
    return () => stopPolling();
  }, []);

  const pollStatus = (jobId: string) => {
    pollRef.current = window.setInterval(async () => {
      try {
        const status = await getExportStatus(jobId);
        setJob(status);
        if (status.status === 'COMPLETED' || status.status === 'FAILED') {
          stopPolling();
        }
      } catch (err) {
        console.error('Poll error:', err);
      }
    }, 1000);
  };

  const handleStartExport = async () => {
    setLoading(true);
    setError('');
    setJob(null);
    stopPolling();

    try {
      const res = await startExport();
      const status = await getExportStatus(res.jobId);
      setJob(status);
      pollStatus(res.jobId);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error');
    } finally {
      setLoading(false);
    }
  };

  const handleDownload = async () => {
    if (!job) return;
    try {
      const { downloadUrl } = await getDownloadUrl(job.id);
      window.open(downloadUrl, '_blank');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Download failed');
    }
  };

  const getStatusColor = (status: ExportStatus) => {
    switch (status) {
      case 'PENDING': return '#6c757d';
      case 'RUNNING': return '#007bff';
      case 'COMPLETED': return '#28a745';
      case 'FAILED': return '#dc3545';
    }
  };

  const formatDate = (date: string | null) => {
    if (!date) return '-';
    return new Date(date).toLocaleString();
  };

  const formatDuration = (start: string | null, end: string | null) => {
    if (!start) return '-';
    const startTime = new Date(start).getTime();
    const endTime = end ? new Date(end).getTime() : Date.now();
    const seconds = Math.floor((endTime - startTime) / 1000);
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    return `${minutes}m ${seconds % 60}s`;
  };

  const formatSpeed = (rowsPerSecond: number | null) => {
    if (!rowsPerSecond) return '-';
    if (rowsPerSecond >= 1000) {
      return `${(rowsPerSecond / 1000).toFixed(1)}K rows/s`;
    }
    return `${rowsPerSecond.toFixed(0)} rows/s`;
  };

  return (
    <div style={{ padding: '20px', maxWidth: '700px', margin: '0 auto' }}>
      <h2>User Export</h2>
      <p style={{ color: '#666', marginBottom: '20px' }}>
        Export all users to CSV file. The file will be uploaded to S3 and you can download it when ready.
      </p>

      <button
        onClick={handleStartExport}
        disabled={loading || (job?.status === 'RUNNING' || job?.status === 'PENDING')}
        style={{
          padding: '12px 24px',
          backgroundColor: loading || job?.status === 'RUNNING' ? '#6c757d' : '#007bff',
          color: 'white',
          border: 'none',
          borderRadius: '6px',
          cursor: loading || job?.status === 'RUNNING' ? 'not-allowed' : 'pointer',
          fontSize: '16px'
        }}
      >
        {loading ? 'Starting...' : job?.status === 'RUNNING' ? 'Exporting...' : 'Start Export'}
      </button>

      {error && (
        <div style={{ marginTop: '15px', padding: '12px', backgroundColor: '#ffe0e0', borderRadius: '6px', color: '#721c24' }}>
          ❌ {error}
        </div>
      )}

      {job && (
        <div style={{ marginTop: '20px', padding: '20px', border: '1px solid #ddd', borderRadius: '8px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
            <h3 style={{ margin: 0 }}>Export Job</h3>
            <span style={{
              padding: '4px 12px',
              backgroundColor: getStatusColor(job.status),
              color: 'white',
              borderRadius: '20px',
              fontSize: '14px'
            }}>
              {job.status}
            </span>
          </div>

          {(job.status === 'RUNNING' || job.status === 'PENDING') && (
            <div style={{ marginBottom: '15px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '5px' }}>
                <span>Progress</span>
                <span>{job.processedRecords.toLocaleString()} / {(job.totalRecords || 0).toLocaleString()} ({job.progressPercent || 0}%)</span>
              </div>
              <div style={{ height: '20px', backgroundColor: '#e9ecef', borderRadius: '10px', overflow: 'hidden' }}>
                <div style={{
                  width: `${job.progressPercent || 0}%`,
                  height: '100%',
                  backgroundColor: '#007bff',
                  transition: 'width 0.3s ease'
                }} />
              </div>
            </div>
          )}

          <table style={{ width: '100%', fontSize: '14px' }}>
            <tbody>
              <tr>
                <td style={{ padding: '8px 0', color: '#666', width: '120px' }}>Job ID</td>
                <td style={{ padding: '8px 0', fontFamily: 'monospace', fontSize: '12px' }}>{job.id}</td>
              </tr>
              <tr>
                <td style={{ padding: '8px 0', color: '#666' }}>Created</td>
                <td style={{ padding: '8px 0' }}>{formatDate(job.createdAt)}</td>
              </tr>
              <tr>
                <td style={{ padding: '8px 0', color: '#666' }}>Duration</td>
                <td style={{ padding: '8px 0' }}>{job.durationFormatted || formatDuration(job.startedAt, job.finishedAt)}</td>
              </tr>
              {job.errorMessage && (
                <tr>
                  <td style={{ padding: '8px 0', color: '#666' }}>Error</td>
                  <td style={{ padding: '8px 0', color: '#dc3545' }}>{job.errorMessage}</td>
                </tr>
              )}
            </tbody>
          </table>

          {/* Metrics Section */}
          {job.status === 'COMPLETED' && (
            <div style={{ marginTop: '15px', padding: '15px', backgroundColor: '#f8f9fa', borderRadius: '6px' }}>
              <h4 style={{ margin: '0 0 10px 0', fontSize: '14px', color: '#666' }}>📊 Export Metrics</h4>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px' }}>
                <div style={{ textAlign: 'center', padding: '10px', backgroundColor: 'white', borderRadius: '6px' }}>
                  <div style={{ fontSize: '18px', fontWeight: 'bold', color: '#007bff' }}>
                    {job.totalRecords?.toLocaleString() || 0}
                  </div>
                  <div style={{ fontSize: '12px', color: '#666' }}>Total Rows</div>
                </div>
                <div style={{ textAlign: 'center', padding: '10px', backgroundColor: 'white', borderRadius: '6px' }}>
                  <div style={{ fontSize: '18px', fontWeight: 'bold', color: '#28a745' }}>
                    {job.fileSizeFormatted || '-'}
                  </div>
                  <div style={{ fontSize: '12px', color: '#666' }}>File Size (gzip)</div>
                </div>
                <div style={{ textAlign: 'center', padding: '10px', backgroundColor: 'white', borderRadius: '6px' }}>
                  <div style={{ fontSize: '18px', fontWeight: 'bold', color: '#fd7e14' }}>
                    {job.compressionPercent ? `${job.compressionPercent.toFixed(1)}%` : '-'}
                  </div>
                  <div style={{ fontSize: '12px', color: '#666' }}>Compression</div>
                </div>
                <div style={{ textAlign: 'center', padding: '10px', backgroundColor: 'white', borderRadius: '6px' }}>
                  <div style={{ fontSize: '18px', fontWeight: 'bold', color: '#17a2b8' }}>
                    {formatSpeed(job.rowsPerSecond)}
                  </div>
                  <div style={{ fontSize: '12px', color: '#666' }}>Speed</div>
                </div>
              </div>
            </div>
          )}

          {job.status === 'COMPLETED' && (
            <button
              onClick={handleDownload}
              style={{
                marginTop: '15px',
                padding: '10px 20px',
                backgroundColor: '#28a745',
                color: 'white',
                border: 'none',
                borderRadius: '6px',
                cursor: 'pointer',
                width: '100%',
                fontSize: '14px'
              }}
            >
              📥 Download CSV
            </button>
          )}
        </div>
      )}
    </div>
  );
}
