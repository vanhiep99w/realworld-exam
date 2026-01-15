import { useState, useEffect, useRef } from 'react';
import { getSystemMetrics, type SystemMetrics } from '@/services/metricsService';

interface MetricsHistory {
  timestamp: number;
  heapUsed: number;
  cpuUsage: number;
}

export function SystemMonitor() {
  const [metrics, setMetrics] = useState<SystemMetrics | null>(null);
  const [history, setHistory] = useState<MetricsHistory[]>([]);
  const [error, setError] = useState('');
  const pollRef = useRef<number | null>(null);

  useEffect(() => {
    const fetchMetrics = async () => {
      try {
        const data = await getSystemMetrics();
        setMetrics(data);
        setError('');
        setHistory(prev => {
          const newHistory = [...prev, {
            timestamp: Date.now(),
            heapUsed: data.heapUsed,
            cpuUsage: data.cpuUsage
          }];
          return newHistory.slice(-30);
        });
      } catch (err) {
        setError('Failed to fetch metrics');
      }
    };

    fetchMetrics();
    pollRef.current = window.setInterval(fetchMetrics, 2000);

    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, []);

  const formatUptime = (seconds: number) => {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    return `${hours}h ${minutes}m ${secs}s`;
  };

  const ProgressBar = ({ value, max, color, label }: { value: number; max: number; color: string; label: string }) => {
    const percentage = max > 0 ? (value / max) * 100 : 0;
    return (
      <div style={{ marginBottom: '12px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px', fontSize: '14px' }}>
          <span>{label}</span>
          <span>{value.toFixed(1)} / {max.toFixed(0)} MB ({percentage.toFixed(1)}%)</span>
        </div>
        <div style={{ height: '20px', backgroundColor: '#e9ecef', borderRadius: '4px', overflow: 'hidden' }}>
          <div style={{
            width: `${Math.min(percentage, 100)}%`,
            height: '100%',
            backgroundColor: color,
            transition: 'width 0.3s ease'
          }} />
        </div>
      </div>
    );
  };

  const MiniChart = ({ data, color, label }: { data: number[]; color: string; label: string }) => {
    const max = Math.max(...data, 1);
    const width = 200;
    const height = 40;
    
    return (
      <div style={{ marginBottom: '15px' }}>
        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>{label}</div>
        <svg width={width} height={height} style={{ backgroundColor: '#f8f9fa', borderRadius: '4px' }}>
          {data.map((value, i) => {
            const x = (i / (data.length - 1 || 1)) * width;
            const y = height - (value / max) * height;
            const nextX = ((i + 1) / (data.length - 1 || 1)) * width;
            const nextY = data[i + 1] !== undefined ? height - (data[i + 1] / max) * height : y;
            return i < data.length - 1 ? (
              <line key={i} x1={x} y1={y} x2={nextX} y2={nextY} stroke={color} strokeWidth="2" />
            ) : null;
          })}
        </svg>
      </div>
    );
  };

  if (error) {
    return (
      <div style={{ padding: '15px', backgroundColor: '#fff3cd', borderRadius: '6px', color: '#856404' }}>
        ⚠️ {error} - Make sure backend is running
      </div>
    );
  }

  if (!metrics) {
    return <div>Loading metrics...</div>;
  }

  return (
    <div style={{ padding: '20px', border: '1px solid #ddd', borderRadius: '8px', backgroundColor: '#fff' }}>
      <h3 style={{ margin: '0 0 15px 0', display: 'flex', alignItems: 'center', gap: '10px' }}>
        📊 System Monitor
        <span style={{ fontSize: '12px', color: '#28a745', fontWeight: 'normal' }}>● Live</span>
      </h3>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
        <div>
          <h4 style={{ margin: '0 0 10px 0', fontSize: '14px', color: '#666' }}>Memory</h4>
          <ProgressBar
            value={metrics.heapUsed}
            max={metrics.heapMax > 0 ? metrics.heapMax : metrics.heapCommitted}
            color="#007bff"
            label="Heap Used"
          />
          <MiniChart
            data={history.map(h => h.heapUsed)}
            color="#007bff"
            label="Heap History (30s)"
          />
        </div>

        <div>
          <h4 style={{ margin: '0 0 10px 0', fontSize: '14px', color: '#666' }}>CPU</h4>
          <div style={{ marginBottom: '12px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px', fontSize: '14px' }}>
              <span>Process CPU</span>
              <span>{metrics.cpuUsage.toFixed(1)}%</span>
            </div>
            <div style={{ height: '20px', backgroundColor: '#e9ecef', borderRadius: '4px', overflow: 'hidden' }}>
              <div style={{
                width: `${Math.min(metrics.cpuUsage, 100)}%`,
                height: '100%',
                backgroundColor: metrics.cpuUsage > 80 ? '#dc3545' : '#28a745',
                transition: 'width 0.3s ease'
              }} />
            </div>
          </div>
          <MiniChart
            data={history.map(h => h.cpuUsage)}
            color="#28a745"
            label="CPU History (30s)"
          />
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px', marginTop: '15px' }}>
        <div style={{ padding: '10px', backgroundColor: '#f8f9fa', borderRadius: '6px', textAlign: 'center' }}>
          <div style={{ fontSize: '20px', fontWeight: 'bold', color: '#007bff' }}>{metrics.threadsLive}</div>
          <div style={{ fontSize: '12px', color: '#666' }}>Threads</div>
        </div>
        <div style={{ padding: '10px', backgroundColor: '#f8f9fa', borderRadius: '6px', textAlign: 'center' }}>
          <div style={{ fontSize: '20px', fontWeight: 'bold', color: '#6c757d' }}>{metrics.threadsPeak}</div>
          <div style={{ fontSize: '12px', color: '#666' }}>Peak Threads</div>
        </div>
        <div style={{ padding: '10px', backgroundColor: '#f8f9fa', borderRadius: '6px', textAlign: 'center' }}>
          <div style={{ fontSize: '20px', fontWeight: 'bold', color: '#17a2b8' }}>{metrics.cpuSystem.toFixed(1)}%</div>
          <div style={{ fontSize: '12px', color: '#666' }}>System CPU</div>
        </div>
        <div style={{ padding: '10px', backgroundColor: '#f8f9fa', borderRadius: '6px', textAlign: 'center' }}>
          <div style={{ fontSize: '16px', fontWeight: 'bold', color: '#28a745' }}>{formatUptime(metrics.uptime)}</div>
          <div style={{ fontSize: '12px', color: '#666' }}>Uptime</div>
        </div>
      </div>
    </div>
  );
}
