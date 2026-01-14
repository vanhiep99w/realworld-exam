import { useState, useEffect } from 'react';
import {
  getPresignedPutUrl,
  getPresignedPostUrl,
  getPresignedGetUrl,
  uploadWithPut,
  uploadWithPost,
  listFiles,
  validateFile,
  ALLOWED_CONTENT_TYPES,
  MAX_FILE_SIZE,
  type S3File
} from '@/services/s3Service';

type UploadMethod = 'PUT' | 'POST';

export function FileUpload() {
  const [file, setFile] = useState<File | null>(null);
  const [method, setMethod] = useState<UploadMethod>('PUT');
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState('');
  const [files, setFiles] = useState<S3File[]>([]);
  const [downloading, setDownloading] = useState<string | null>(null);

  const loadFiles = async () => {
    try {
      const data = await listFiles();
      setFiles(data);
    } catch (err) {
      console.error('Failed to load files:', err);
    }
  };

  useEffect(() => {
    loadFiles();
  }, []);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0] || null;
    setFile(selected);
    setMessage('');
    
    if (selected) {
      const error = validateFile(selected);
      if (error) {
        setMessage(`⚠️ ${error}`);
      }
    }
  };

  const handleUpload = async () => {
    if (!file) {
      setMessage('Please select a file');
      return;
    }

    const validationError = validateFile(file);
    if (validationError) {
      setMessage(`❌ ${validationError}`);
      return;
    }

    setUploading(true);
    setMessage('');

    try {
      const key = `uploads/${Date.now()}-${file.name}`;
      const contentType = file.type || 'application/octet-stream';

      if (method === 'PUT') {
        setMessage('Getting presigned PUT URL...');
        const { url } = await getPresignedPutUrl(key, contentType, file.size);
        setMessage('Uploading with PUT...');
        await uploadWithPut(url, file);
      } else {
        setMessage('Getting presigned POST URL with policy...');
        const { url, fields } = await getPresignedPostUrl(key, contentType);
        setMessage('Uploading with POST (form-data)...');
        await uploadWithPost(url, fields, file);
      }

      setMessage(`✅ Upload successful! Key: ${key}`);
      setFile(null);
      
      const input = document.querySelector('input[type="file"]') as HTMLInputElement;
      if (input) input.value = '';
      
      loadFiles();
    } catch (err) {
      setMessage(`❌ ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setUploading(false);
    }
  };

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const handleDownload = async (key: string) => {
    setDownloading(key);
    try {
      const { url } = await getPresignedGetUrl(key);
      window.open(url, '_blank');
    } catch (err) {
      setMessage(`❌ Download failed: ${err instanceof Error ? err.message : 'Unknown error'}`);
    } finally {
      setDownloading(null);
    }
  };

  return (
    <div style={{ padding: '20px', maxWidth: '700px', margin: '0 auto' }}>
      <h2>S3 Presigned URL Demo</h2>

      <div style={{ marginBottom: '20px', padding: '15px', backgroundColor: '#f5f5f5', borderRadius: '8px' }}>
        <h4 style={{ margin: '0 0 10px 0' }}>Upload Constraints (enforced by S3 policy):</h4>
        <ul style={{ margin: 0, paddingLeft: '20px', fontSize: '14px' }}>
          <li>Max size: <strong>{formatSize(MAX_FILE_SIZE)}</strong></li>
          <li>Types: Images (JPEG, PNG, GIF, WebP), PDF, ZIP</li>
        </ul>
      </div>

      <div style={{ marginBottom: '20px', padding: '20px', border: '1px solid #ccc', borderRadius: '8px' }}>
        <h3>Upload File</h3>

        <div style={{ marginBottom: '15px', padding: '10px', backgroundColor: '#e8f4fd', borderRadius: '4px' }}>
          <label style={{ display: 'block', marginBottom: '8px' }}>
            <input
              type="radio"
              name="method"
              value="PUT"
              checked={method === 'PUT'}
              onChange={() => setMethod('PUT')}
            /> <strong>PUT</strong> - Direct upload, size validated via Content-Length header
          </label>
          <label style={{ display: 'block' }}>
            <input
              type="radio"
              name="method"
              value="POST"
              checked={method === 'POST'}
              onChange={() => setMethod('POST')}
            /> <strong>POST</strong> - Form-based upload with policy (content-length-range enforced by S3)
          </label>
        </div>

        <input
          type="file"
          accept={ALLOWED_CONTENT_TYPES.join(',')}
          onChange={handleFileChange}
          disabled={uploading}
          style={{ marginBottom: '10px', display: 'block' }}
        />

        {file && (
          <p style={{ fontSize: '14px', color: '#666' }}>
            Selected: {file.name} ({formatSize(file.size)}, {file.type || 'unknown type'})
          </p>
        )}

        <button
          onClick={handleUpload}
          disabled={uploading || !file}
          style={{
            padding: '10px 20px',
            backgroundColor: uploading ? '#ccc' : '#007bff',
            color: 'white',
            border: 'none',
            borderRadius: '4px',
            cursor: uploading ? 'not-allowed' : 'pointer'
          }}
        >
          {uploading ? 'Uploading...' : `Upload with ${method}`}
        </button>

        {message && (
          <p style={{ 
            marginTop: '10px', 
            padding: '10px', 
            backgroundColor: message.startsWith('❌') ? '#ffe0e0' : 
                           message.startsWith('⚠️') ? '#fff3cd' : '#e0ffe0', 
            borderRadius: '4px' 
          }}>
            {message}
          </p>
        )}
      </div>

      <div style={{ padding: '20px', border: '1px solid #ccc', borderRadius: '8px' }}>
        <h3>Uploaded Files</h3>
        <button onClick={loadFiles} style={{ marginBottom: '10px' }}>
          Refresh
        </button>

        {files.length === 0 ? (
          <p>No files uploaded yet</p>
        ) : (
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {files.map((f) => (
              <li
                key={f.key}
                style={{
                  padding: '8px',
                  borderBottom: '1px solid #eee',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center'
                }}
              >
                <span style={{ wordBreak: 'break-all', flex: 1 }}>{f.key}</span>
                <span style={{ color: '#666', marginLeft: '10px', whiteSpace: 'nowrap' }}>
                  {formatSize(f.size)}
                </span>
                <button
                  onClick={() => handleDownload(f.key)}
                  disabled={downloading === f.key}
                  style={{
                    marginLeft: '10px',
                    padding: '4px 12px',
                    backgroundColor: downloading === f.key ? '#ccc' : '#28a745',
                    color: 'white',
                    border: 'none',
                    borderRadius: '4px',
                    cursor: downloading === f.key ? 'not-allowed' : 'pointer',
                    fontSize: '12px'
                  }}
                >
                  {downloading === f.key ? '...' : 'Download'}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
