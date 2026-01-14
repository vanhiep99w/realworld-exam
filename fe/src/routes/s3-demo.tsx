import { createFileRoute } from '@tanstack/react-router';
import { FileUpload } from '@/components/FileUpload';

export const Route = createFileRoute('/s3-demo')({
  component: S3DemoPage
});

function S3DemoPage() {
  return (
    <div>
      <FileUpload />
    </div>
  );
}
