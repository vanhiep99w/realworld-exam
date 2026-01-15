const ACTUATOR_BASE = 'http://localhost:8080/actuator';

export interface MetricValue {
  name: string;
  description: string;
  baseUnit: string | null;
  measurements: { statistic: string; value: number }[];
  availableTags: { tag: string; values: string[] }[];
}

export interface SystemMetrics {
  heapUsed: number;
  heapMax: number;
  heapCommitted: number;
  nonHeapUsed: number;
  cpuUsage: number;
  cpuSystem: number;
  threadsLive: number;
  threadsPeak: number;
  uptime: number;
}

async function getMetric(name: string): Promise<number> {
  try {
    const res = await fetch(`${ACTUATOR_BASE}/metrics/${name}`);
    if (!res.ok) return 0;
    const data: MetricValue = await res.json();
    return data.measurements[0]?.value ?? 0;
  } catch {
    return 0;
  }
}

export async function getSystemMetrics(): Promise<SystemMetrics> {
  const [
    heapUsed,
    heapMax,
    heapCommitted,
    nonHeapUsed,
    cpuUsage,
    cpuSystem,
    threadsLive,
    threadsPeak,
    uptime
  ] = await Promise.all([
    getMetric('jvm.memory.used').then(v => v / (1024 * 1024)), // Convert to MB
    getMetric('jvm.memory.max').then(v => v / (1024 * 1024)),
    getMetric('jvm.memory.committed').then(v => v / (1024 * 1024)),
    getMetric('jvm.memory.used').then(v => v / (1024 * 1024)),
    getMetric('process.cpu.usage').then(v => v * 100), // Convert to percentage
    getMetric('system.cpu.usage').then(v => v * 100),
    getMetric('jvm.threads.live'),
    getMetric('jvm.threads.peak'),
    getMetric('process.uptime')
  ]);

  return {
    heapUsed,
    heapMax,
    heapCommitted,
    nonHeapUsed,
    cpuUsage,
    cpuSystem,
    threadsLive,
    threadsPeak,
    uptime
  };
}

export async function getHealth(): Promise<{ status: string }> {
  const res = await fetch(`${ACTUATOR_BASE}/health`);
  return res.json();
}
