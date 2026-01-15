import { createFileRoute } from '@tanstack/react-router';
import { UserExport } from '@/components/UserExport';
import { SystemMonitor } from '@/components/SystemMonitor';

export const Route = createFileRoute('/export')({
  component: ExportPage
});

function ExportPage() {
  return (
    <div style={{ padding: '20px', maxWidth: '800px', margin: '0 auto' }}>
      <SystemMonitor />
      <div style={{ marginTop: '20px' }}>
        <UserExport />
      </div>
    </div>
  );
}
